package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.ai.guard.Privacy;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.commodity.CommodityKind;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.commodity.CommodityService.CustomCommodityRequest;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.pricefeed.StockService;
import ir.karname.pricefeed.TsetmcClient.StockInstrument;
import ir.karname.transaction.Transaction;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionSource;
import ir.karname.transaction.TransactionType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shares or fund units on the Tehran exchange: the symbol is followed (its closing price fetched from
 * TSETMC), kept in an investment account of its own, and either held already or bought now from a
 * Toman account, the broker's fee counted in the cost.
 */
@Component
class SecurityProposals implements ProposalKind<SecurityProposals.Security> {

    public enum Mode {
        /** Already held: the account opens with the quantity. */
        HOLDING,
        /** Bought: a transfer from a Toman account, fee included. */
        BUY
    }

    /** {@code commodity}: the user's unit for the symbol, or null until it is added on applying. */
    public record Security(String instrumentId, String symbol, String nameFa, String commodity, Mode mode, BigDecimal quantity,
            BigDecimal totalToman, BigDecimal feeToman, Long cashAccountId, Long holdingAccountId, String holdingName, LocalDate date) {

        Security withCommodity(String code) {
            return new Security(instrumentId, symbol, nameFa, code, mode, quantity, totalToman, feeToman, cashAccountId, holdingAccountId,
                    holdingName, date);
        }
    }

    record Outcome(long holdingAccountId, boolean newAccount, Transaction purchase) {
    }

    private final StockService stocks;
    private final CommodityService commodities;
    private final AccountService accounts;
    private final TransactionService transactions;

    SecurityProposals(StockService stocks, CommodityService commodities, AccountService accounts, TransactionService transactions) {
        this.stocks = stocks;
        this.commodities = commodities;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override
    public String kind() {
        return "SECURITY";
    }

    @Override
    public String tool() {
        return "propose_security";
    }

    @Override
    public String label() {
        return "پیشنهاد سهام";
    }

    @Override
    public String description() {
        return "Proposes shares or fund units on the Tehran exchange (بورس و فرابورس), for the user to confirm: either ones the user "
                + "already holds (HOLDING) or a purchase from a Toman account (BUY, with the price per share or the total and the "
                + "broker's fee). The symbol is looked up on the exchange and its closing price is then fetched automatically; each "
                + "symbol is kept in its own investment account. For a sale, use propose_transactions (a transfer from the holding "
                + "account to the bank account).";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("symbol", JsonSchema.string("The symbol as the user said it, e.g. «فولاد», «خودرو», «عیار»").maxLength(30))
                .required("mode", JsonSchema.enumOf("HOLDING (the user has them) or BUY (bought, money from an account)",
                        List.of("HOLDING", "BUY")));
        Inputs.quantity(b, "quantity", "How many shares or units", true);
        Inputs.money(b, "price_per_share", "BUY: the price of one share", false);
        Inputs.money(b, "total_cost", "BUY: the total paid for all of them, when the user gave that instead of a per-share price", false);
        Inputs.money(b, "fee", "BUY: the broker's fee (کارمزد), if stated", false);
        return b.optional("cash_account", Inputs.reference("BUY: the Toman account that paid; default the everyday account"))
                .optional("holding_account", Inputs.reference("The investment account the shares are kept in, when the user has more "
                        + "than one for this symbol; default the one they have, else a new one"))
                .optional("date", Inputs.date("BUY: when it was bought; HOLDING: the date of the balance; default today"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<Security> payloadType() {
        return Security.class;
    }

    @Override
    public List<Prepared<Security>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        String said = Inputs.text(in, "symbol");
        String key = PersianText.normalizeForSearch(said);
        Mode mode = Inputs.enumValue(in, "mode", Mode.class);
        BigDecimal quantity = Inputs.quantity(in, "quantity");

        // the user's own unit for the symbol first: no need to ask the exchange
        CommodityView own = lookup.units().stream()
                .filter(u -> u.kind() == CommodityKind.SECURITY && u.stock() != null && u.stock().instrumentId() != null)
                .filter(u -> PersianText.normalizeForSearch(u.stock().symbol()).equals(key) || PersianText.normalizeForSearch(u.nameFa()).equals(key))
                .findFirst().orElse(null);
        String instrumentId;
        String symbol;
        String name;
        if (own != null) {
            instrumentId = own.stock().instrumentId();
            symbol = own.stock().symbol();
            name = own.nameFa();
        } else {
            StockInstrument found = instrument(said, key);
            instrumentId = found.instrumentId();
            symbol = found.symbol();
            name = found.nameFa();
        }

        List<Account> holders = own == null ? List.of()
                : lookup.accounts().stream().filter(a -> own.code().equals(lookup.unitOf(a))).toList();
        Account holding = lookup.account("holding_account", Inputs.text(in, "holding_account"), holders::contains,
                "an account kept in " + symbol);
        if (holding == null && holders.size() > 1) {
            throw new IllegalArgumentException("holding_account: the user keeps " + symbol + " in several accounts: " + holders.stream()
                    .map(a -> a.getId() + " «" + Privacy.mask(a.getName()) + "»").collect(Collectors.joining(", ")) + "; pass one");
        }
        if (holding == null && holders.size() == 1) {
            holding = holders.getFirst();
        }
        LocalDate date = Inputs.date(in, "date");
        if (date == null) {
            date = request.today();
        }
        if (mode == Mode.HOLDING) {
            if (holding != null) {
                throw new IllegalArgumentException("mode: the user already keeps " + symbol + " in «" + Privacy.mask(holding.getName())
                        + "»; a changed count is recorded there with «تطبیق موجودی» on the account's page, and a purchase with mode BUY");
            }
            warnings.add("بهای خرید این سهم‌ها ثبت نمی‌شود؛ سود و زیان از قیمت روز ثبت حساب می‌شود.");
            return List.of(Prepared.of(new Security(instrumentId, symbol, name, own == null ? null : own.code(), mode, quantity, null, null,
                    null, null, "سهام " + symbol, date), warnings));
        }

        BigDecimal total = Inputs.toman(in, "total_cost");
        BigDecimal perShare = Inputs.toman(in, "price_per_share");
        if (total == null && perShare == null) {
            throw new IllegalArgumentException("price_per_share: a purchase needs the price per share or the total cost; ask the user");
        }
        if (total == null) {
            total = perShare.multiply(quantity).setScale(0, RoundingMode.HALF_EVEN);
        }
        if (own != null && own.latestPrice() != null && own.latestPrice().priceToman().signum() > 0) {
            BigDecimal price = total.divide(quantity, MathContext.DECIMAL64);
            BigDecimal off = price.divide(own.latestPrice().priceToman(), MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
            if (off.compareTo(new BigDecimal("0.5")) > 0) {
                warnings.add("قیمت هر سهم بیش از ۵۰٪ با آخرین قیمت این نماد فرق دارد؛ تومان یا ریال بودن آن را بررسی کنید.");
            }
        }
        Account cash = lookup.account("cash_account", Inputs.text(in, "cash_account"), lookup::isTomanAsset, "a Toman account that is not a debt");
        if (cash == null) {
            cash = lookup.everyday().orElseThrow(() -> new IllegalArgumentException("cash_account: the user has no Toman bank, wallet or "
                    + "cash account; ask which account paid"));
            warnings.add("حساب پرداخت مشخص نبود؛ «" + cash.getName() + "» در نظر گرفته شد.");
        }
        return List.of(Prepared.of(new Security(instrumentId, symbol, name, own == null ? null : own.code(), mode, quantity, total,
                Inputs.toman(in, "fee"), cash.getId(), holding == null ? null : holding.getId(), "سهام " + symbol, date), warnings));
    }

    /** The exchange's instrument for what the user said: the exact symbol, else the only match. */
    private StockInstrument instrument(String said, String key) {
        List<StockInstrument> results = stocks.search(said);
        List<StockInstrument> exact = results.stream().filter(r -> PersianText.normalizeForSearch(r.symbol()).equals(key)).toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        if (exact.isEmpty() && results.size() == 1) {
            return results.getFirst();
        }
        String list = (exact.isEmpty() ? results : exact).stream().limit(10).map(r -> "«" + r.symbol() + "» (" + r.nameFa() + ")")
                .collect(Collectors.joining(", "));
        throw new IllegalArgumentException("symbol: " + (results.isEmpty() ? "the exchange has no symbol matching «" + said + "»"
                : "«" + said + "» matches several symbols: " + list + "; pass the exact symbol, or ask the user"));
    }

    @Override
    public Security beforeApply(long userId, Security p) {
        // following the symbol asks the exchange, so it happens before the transaction; doing it twice is harmless
        return p.commodity() != null ? p : p.withCommodity(stocks.create(userId, p.instrumentId()).code());
    }

    @Override
    public Object execute(long userId, Security p) {
        String code = p.commodity() != null ? p.commodity()
                // only when checking the proposal: a stand-in for the symbol, rolled back with the rest
                : commodities.createCustom(userId, new CustomCommodityRequest(p.symbol(), "سهم", CommodityKind.SECURITY, 0)).getCode();
        Long holdingId = p.holdingAccountId();
        boolean created = false;
        if (holdingId == null) {
            Long commodityId = commodities.require(userId, code).getId();
            Account existing = accounts.list(userId).stream().filter(a -> !a.isArchived() && a.getCommodityId().equals(commodityId))
                    .findFirst().orElse(null);
            if (existing != null && p.mode() == Mode.HOLDING) {
                throw ApiException.conflict("ai.securityHeld", existing.getName());
            }
            if (existing != null) {
                holdingId = existing.getId();
            } else {
                Account account = accounts.create(userId, new AccountRequest(p.holdingName(), AccountType.INVESTMENT, code, null, null, null,
                        null, true, null, p.mode() == Mode.HOLDING ? p.quantity() : null, p.mode() == Mode.HOLDING ? p.date() : null));
                holdingId = account.getId();
                created = true;
            }
        }
        Transaction purchase = null;
        if (p.mode() == Mode.BUY) {
            purchase = transactions.create(userId, new TransactionRequest(TransactionType.TRANSFER, p.date(), p.cashAccountId(), p.totalToman(),
                    holdingId, p.quantity(), p.feeToman(), null, "خرید " + PersianText.toPersianDigits(p.quantity().toPlainString()) + " سهم "
                            + p.symbol(), null, List.of()), TransactionSource.AI, null);
        }
        return new Outcome(holdingId, created, purchase);
    }

    @Override
    public Card card(long userId, Security p, Object result) {
        Outcome o = (Outcome) result;
        String holding = o.newAccount() ? p.holdingName() : accounts.require(userId, o.holdingAccountId()).getName();
        Card.Builder card = Card.builder((p.mode() == Mode.BUY ? "خرید " : "") + "سهام «" + p.symbol() + "»")
                .text("نماد", p.symbol())
                .text("نام", p.nameFa().equals(p.symbol()) ? null : p.nameFa())
                .number("تعداد", p.quantity());
        if (p.mode() == Mode.BUY) {
            card.money("مبلغ کل", p.totalToman(), true)
                    .money("قیمت هر سهم", p.totalToman().divide(p.quantity(), 2, RoundingMode.HALF_EVEN))
                    .money("کارمزد", p.feeToman())
                    .text("از حساب", accounts.require(userId, p.cashAccountId()).getName())
                    .date("تاریخ خرید", p.date());
        } else {
            card.date("تاریخ موجودی", p.date());
        }
        card.text("حساب نگهداری", (o.newAccount() ? "حساب تازه‌ی " : "") + "«" + holding + "»");
        if (p.commodity() == null) {
            card.effect("نماد «" + p.symbol() + "» به دارایی‌های شما اضافه می‌شود و قیمت پایانی‌اش خودکار از بورس (TSETMC) گرفته می‌شود.");
        }
        if (o.newAccount()) {
            card.effect("حساب «" + p.holdingName() + "» از نوع بورس و صندوق ساخته می‌شود" + (p.mode() == Mode.HOLDING
                    ? " و " + PersianText.toPersianDigits(p.quantity().toPlainString()) + " سهم موجودی اول آن می‌شود." : "."));
        }
        if (p.mode() == Mode.BUY) {
            card.effect("مبلغ کل" + (p.feeToman() != null ? " و کارمزد" : "") + " از «" + accounts.require(userId, p.cashAccountId()).getName()
                    + "» کم و سهم‌ها به «" + holding + "» اضافه می‌شود؛ کارمزد جزو بهای خرید حساب می‌شود.");
        }
        return card.build();
    }

    @Override
    public String link(Security payload, Object result) {
        return "/accounts/" + ((Outcome) result).holdingAccountId();
    }
}
