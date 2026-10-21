package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.account.AccountViews;
import ir.karname.account.AccountViews.AccountView;
import ir.karname.account.Bank;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A new account of any kind but a bank loan (which has its own schedule) and shares (which follow a symbol). */
@Component
class AccountProposals implements ProposalKind<AccountRequest> {

    private static final List<AccountType> TYPES = Arrays.stream(AccountType.values()).filter(t -> t != AccountType.LOAN).toList();
    /** The unit each type starts with, as the account form picks it. */
    private static final Map<AccountType, String> DEFAULT_UNIT = Map.of(AccountType.CURRENCY, "USD", AccountType.GOLD, "GOLD18",
            AccountType.CRYPTO, "USDT");

    private final AccountService accounts;
    private final AccountViews views;

    AccountProposals(AccountService accounts, AccountViews views) {
        this.accounts = accounts;
        this.views = views;
    }

    @Override
    public String kind() {
        return "ACCOUNT";
    }

    @Override
    public String tool() {
        return "propose_account";
    }

    @Override
    public String label() {
        return "پیشنهاد حساب";
    }

    @Override
    public String description() {
        return "Proposes a new account for the user to confirm: a bank account, cash, an e-wallet, foreign currency, gold or coins, "
                + "crypto, an investment, property or a vehicle, money someone owes the user (RECEIVABLE), money the user owes a "
                + "person (DEBT) or an instalment purchase (CREDIT). Not for bank loans (propose_loan) or shares and funds "
                + "(propose_security). The opening balance is what the account holds today (for DEBT and CREDIT, what is owed).";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("name", JsonSchema.string("Short name, e.g. «ملت حقوق» or «طلب از علی»").maxLength(100))
                .required("type", JsonSchema.enumOf("Kind of account", TYPES.stream().map(Enum::name).toList()))
                .optional("unit", JsonSchema.string("The unit it is kept in, as a code or name (USD, «یورو», GOLD18, «سکه امامی», USDT…); "
                        + "default Toman, or US dollars for CURRENCY, 18k gold grams for GOLD and Tether for CRYPTO").maxLength(60))
                .optional("bank", JsonSchema.enumOf("The bank: " + Labels.banks(), Arrays.stream(Bank.values()).map(Enum::name).toList()))
                .optional("card_last4", JsonSchema.array("Last four digits of its card or account numbers, to match bank SMS",
                        JsonSchema.string(null).maxLength(8)).maxItems(5))
                .optional("counterparty", JsonSchema.string("For DEBT and RECEIVABLE: who").maxLength(100));
        b.required("opening_balance", JsonSchema.number("What it holds today, as the user said it before any scale word; 0 when "
                + "unknown or empty").min(0));
        b.optional("opening_balance_scale", JsonSchema.enumOf("Scale word: ONE (default), THOUSAND, MILLION or BILLION",
                List.of("ONE", "THOUSAND", "MILLION", "BILLION")));
        b.optional("opening_balance_unit", JsonSchema.enumOf("TOMAN (default) or RIAL for a Toman account; ACCOUNT when the balance is in "
                + "the account's own unit (dollars, grams, coins…), which other units need", List.of("TOMAN", "RIAL", "ACCOUNT")));
        return b.optional("opening_date", Inputs.date("Date of the opening balance; default today"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<AccountRequest> payloadType() {
        return AccountRequest.class;
    }

    @Override
    public List<Prepared<AccountRequest>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        AccountType type = Inputs.enumValue(in, "type", AccountType.class);
        String name = Inputs.text(in, "name");
        CommodityView unit = lookup.unit("unit", Inputs.text(in, "unit"));
        String code = unit != null ? unit.code() : DEFAULT_UNIT.getOrDefault(type, "IRT");
        if (type.isLiability() && !"IRT".equals(code)) {
            throw new IllegalArgumentException("unit: debts and instalment purchases are kept in Toman");
        }
        BigDecimal opening = null;
        if (Inputs.positive(in, "opening_balance")) {
            boolean inUnit = "ACCOUNT".equals(Inputs.text(in, "opening_balance_unit"));
            if (!"IRT".equals(code) && !inUnit) {
                throw new IllegalArgumentException("opening_balance: this account is kept in " + code + "; give the balance in that unit "
                        + "with opening_balance_unit ACCOUNT");
            }
            opening = inUnit ? Inputs.quantity(in, "opening_balance") : Inputs.toman(in, "opening_balance");
        }
        String key = PersianText.normalizeForSearch(name);
        if (lookup.accounts().stream().anyMatch(a -> PersianText.normalizeForSearch(a.getName()).equals(key))) {
            warnings.add("حسابی با همین نام دارید؛ مطمئن شوید تکراری نیست.");
        }
        List<String> hints = Inputs.texts(in, "card_last4");
        return List.of(Prepared.of(new AccountRequest(name, type, code, Inputs.enumValue(in, "bank", Bank.class), hints.isEmpty() ? null : hints,
                Inputs.text(in, "counterparty"), null, true, null, opening, Inputs.date(in, "opening_date")), warnings));
    }

    @Override
    public Object execute(long userId, AccountRequest payload) {
        return accounts.create(userId, payload);
    }

    @Override
    public Card card(long userId, AccountRequest p, Object result) {
        AccountView view = views.view(userId, (Account) result);
        boolean liability = p.type().isLiability();
        Card.Builder card = Card.builder("حساب «" + p.name() + "»")
                .text("نوع", Labels.accountType(p.type()))
                .text("بانک", Labels.bank(p.bank()))
                .text("شماره کارت یا حساب (چهار رقم آخر)", p.identifierHints() == null ? null : String.join("، ", p.identifierHints()))
                .text("طرف حساب", p.counterparty());
        BigDecimal opening = p.openingBalance() == null ? BigDecimal.ZERO : p.openingBalance();
        card.quantity(liability ? "مبلغ بدهی" : "موجودی", opening, p.commodity(), true);
        if (opening.signum() != 0) {
            card.date("تاریخ موجودی", p.openingDate());
            card.effect("مانده‌ی اول دوره در این حساب ثبت می‌شود" + (p.openingDate() == null ? " (به تاریخ امروز)." : "."));
        }
        if (!"IRT".equals(p.commodity())) {
            if (view.priced()) {
                card.money("ارزش امروز", view.valueToman());
            } else {
                card.warning("برای این واحد قیمتی ثبت نشده؛ ارزش حساب تا ثبت قیمت در دارایی خالص نمی‌آید.");
            }
        }
        return card.build();
    }

    @Override
    public String link(AccountRequest payload, Object result) {
        return "/accounts/" + ((Account) result).getId();
    }
}
