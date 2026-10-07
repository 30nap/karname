package ir.karname.ai.capture;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountType;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.category.MerchantRuleService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.persian.PersianText;
import ir.karname.transaction.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns what a model read from text (a sentence, a bank SMS) into transaction drafts the user
 * confirms. The model reports amounts as written with their scale word and unit; the arithmetic
 * (thousands, millions, Rial to Toman) happens here, and every id it names is checked against
 * the user's own accounts and categories.
 */
@Component
public class DraftBuilder {

    /** Draft references: transactions recorded from them carry it, so recording twice is harmless. */
    public static final Pattern REF = Pattern.compile("^(ai|sms):[0-9a-f]{32}$");
    static final int MAX_DESCRIPTION = 300;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e16");
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    /** The scale word after a number: «هزار», «میلیون», «میلیارد». */
    public enum Scale {
        ONE(BigDecimal.ONE), THOUSAND(BigDecimal.valueOf(1_000)), MILLION(BigDecimal.valueOf(1_000_000)),
        BILLION(BigDecimal.valueOf(1_000_000_000));

        private final BigDecimal factor;

        Scale(BigDecimal factor) {
            this.factor = factor;
        }
    }

    /** What the amount is counted in: Toman, Rial, or the account's own unit (dollars, grams…). */
    public enum AmountUnit {
        TOMAN, RIAL, ACCOUNT
    }

    /** What a model proposes, before checking. {@code date} is Jalali text; null fields were not stated. */
    public record Proposal(TransactionType type, BigDecimal amount, Scale scale, AmountUnit unit, String date, Long accountId,
            Long toAccountId, BigDecimal toAmount, Long categoryId, String description, Confidence confidence, String note) {
    }

    /** A transaction for the user to confirm; {@code warnings} explain what needs a look. */
    public record Draft(String ref, TransactionType type, LocalDate date, Long accountId, BigDecimal amount, Long toAccountId,
            BigDecimal toAmount, Long categoryId, String description, Confidence confidence, List<String> warnings, boolean duplicate) {
    }

    /** The user's accounts and categories, looked up once for a batch of proposals. */
    public record Context(long userId, LocalDate today, Map<Long, Account> accounts, Map<Long, Commodity> commodities,
            Map<Long, Category> categories, Account defaultAccount, int everydayAccounts) {

        boolean isToman(Account account) {
            Commodity c = commodities.get(account.getCommodityId());
            return c != null && c.isToman();
        }

        String unitName(Account account) {
            Commodity c = commodities.get(account.getCommodityId());
            return c == null ? "" : c.getUnitFa();
        }
    }

    private final AccountService accounts;
    private final CategoryService categories;
    private final CommodityRepository commodities;
    private final MerchantRuleService merchantRules;
    private final JdbcClient jdbc;
    private final Clock clock;

    public DraftBuilder(AccountService accounts, CategoryService categories, CommodityRepository commodities, MerchantRuleService merchantRules,
            JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.categories = categories;
        this.commodities = commodities;
        this.merchantRules = merchantRules;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The schema of one proposed transaction, shared by quick add and the chat's draft tool. */
    public static JsonSchema proposalSchema() {
        return JsonSchema.object("One transaction the user described")
                .required("type", JsonSchema.enumOf("EXPENSE (money spent), INCOME (money received) or TRANSFER (between the user's own "
                        + "accounts, including buying or selling currency, gold or coins)", List.of("EXPENSE", "INCOME", "TRANSFER")))
                .required("amount", JsonSchema.number("The number exactly as the user wrote it, before the scale word: «۱۸۰ تومن» → 180, "
                        + "«۲.۵ میلیون» → 2.5"))
                .required("scale", JsonSchema.enumOf("The multiplier word after the number: none → ONE, «هزار» → THOUSAND, «میلیون/ملیون/تومن "
                        + "for big purchases» → MILLION, «میلیارد» → BILLION. Colloquially «تومن» after a small number usually means thousand "
                        + "Toman for everyday spending («ناهار ۲۰۰ تومن» → 200 THOUSAND) but million for big purchases («ماشین ۸۰۰ تومن» → "
                        + "800 MILLION); mark confidence MEDIUM or LOW when unsure", List.of("ONE", "THOUSAND", "MILLION", "BILLION")))
                .required("unit", JsonSchema.enumOf("TOMAN or RIAL for Iranian money (ریال → RIAL, تومان/تومن → TOMAN); ACCOUNT when the amount "
                        + "is in the account's own unit (dollars, euros, grams of gold, coins…)", List.of("TOMAN", "RIAL", "ACCOUNT")))
                .required("date", JsonSchema.string("Jalali date as yyyy/mm/dd, resolving words like «دیروز» or «پنجشنبه» from today's "
                        + "date in the context; today when not stated"))
                .optional("account_id", JsonSchema.integer("Id of the account the money left (EXPENSE, TRANSFER) or entered (INCOME); "
                        + "omit when unclear"))
                .optional("to_account_id", JsonSchema.integer("For TRANSFER: id of the receiving account"))
                .optional("to_amount", JsonSchema.number("For a TRANSFER between different units (e.g. buying dollars or gold with Toman): "
                        + "the amount the receiving account got, in its own unit, as a final number"))
                .optional("category_id", JsonSchema.integer("For EXPENSE or INCOME: id of the best-matching category of that kind; omit "
                        + "when none fits"))
                .required("description", JsonSchema.string("Short Persian description, e.g. «ناهار» or «اسنپ»").maxLength(MAX_DESCRIPTION))
                .required("confidence", JsonSchema.enumOf("HIGH when everything is stated, MEDIUM or LOW when the amount's scale or "
                        + "another detail is a guess", List.of("HIGH", "MEDIUM", "LOW")))
                .optional("note", JsonSchema.string("In Persian, what is uncertain (only when confidence is not HIGH)").maxLength(300))
                .build();
    }

    /** A proposal from JSON that conforms to {@link #proposalSchema()}. */
    public static Proposal proposal(JsonNode node) {
        return new Proposal(
                TransactionType.valueOf(node.get("type").asString()),
                node.get("amount").decimalValue(),
                Scale.valueOf(node.get("scale").asString()),
                AmountUnit.valueOf(node.get("unit").asString()),
                node.path("date").asString(null),
                node.hasNonNull("account_id") ? node.get("account_id").asLong() : null,
                node.hasNonNull("to_account_id") ? node.get("to_account_id").asLong() : null,
                node.hasNonNull("to_amount") ? node.get("to_amount").decimalValue() : null,
                node.hasNonNull("category_id") ? node.get("category_id").asLong() : null,
                node.path("description").asString(""),
                Confidence.valueOf(node.get("confidence").asString()),
                node.hasNonNull("note") ? node.get("note").asString() : null);
    }

    @Transactional(readOnly = true)
    public Context context(long userId) {
        List<Account> active = accounts.list(userId).stream().filter(a -> !a.isArchived()).toList();
        Map<Long, Account> accountMap = active.stream().collect(Collectors.toMap(Account::getId, Function.identity(), (a, b) -> a,
                java.util.LinkedHashMap::new));
        Map<Long, Commodity> commodityMap = commodities.findAllById(active.stream().map(Account::getCommodityId).distinct().toList())
                .stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
        Map<Long, Category> categoryMap = categories.list(userId).stream().filter(c -> !c.isArchived())
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<Account> everyday = active.stream()
                .filter(a -> a.getType() == AccountType.BANK || a.getType() == AccountType.EWALLET || a.getType() == AccountType.CASH)
                .filter(a -> {
                    Commodity c = commodityMap.get(a.getCommodityId());
                    return c != null && c.isToman();
                })
                .sorted(Comparator.comparing((Account a) -> a.getType() == AccountType.CASH).thenComparing(Account::getSortOrder)
                        .thenComparing(Account::getId))
                .toList();
        return new Context(userId, LocalDate.now(clock), accountMap, commodityMap, categoryMap, everyday.isEmpty() ? null : everyday.getFirst(),
                everyday.size());
    }

    public Draft build(Context ctx, Proposal p, String prefix) {
        List<String> warnings = new ArrayList<>();
        TransactionType type = p.type() == null ? TransactionType.EXPENSE : p.type();

        LocalDate date = ctx.today();
        if (p.date() != null && !p.date().isBlank()) {
            try {
                date = JalaliDate.parse(p.date().strip().replace('-', '/')).toGregorian();
            } catch (IllegalArgumentException e) {
                warnings.add("تاریخ «" + p.date() + "» خوانده نشد؛ امروز در نظر گرفته شد.");
            }
        }
        if (date.isAfter(ctx.today())) {
            warnings.add("تاریخ در آینده است.");
        }

        Account account = p.accountId() == null ? null : ctx.accounts().get(p.accountId());
        if (account == null) {
            account = ctx.defaultAccount();
            if (account != null && ctx.everydayAccounts() > 1) {
                warnings.add("حساب مشخص نبود؛ «" + account.getName() + "» انتخاب شد.");
            } else if (account == null) {
                warnings.add("حسابی برای این تراکنش پیدا نشد؛ یکی را انتخاب کنید.");
            }
        }

        BigDecimal amount = amount(p.amount(), p.scale(), p.unit());
        if (amount == null) {
            warnings.add("مبلغ خوانده نشد.");
        } else if (account != null && !ctx.isToman(account) && p.unit() != AmountUnit.ACCOUNT) {
            warnings.add("مبلغ به تومان آمده ولی حساب «" + account.getName() + "» به " + ctx.unitName(account) + " است؛ مبلغ را اصلاح کنید.");
        }

        Long toAccountId = null;
        BigDecimal toAmount = null;
        Long categoryId = null;
        if (type == TransactionType.TRANSFER) {
            Account to = p.toAccountId() == null ? null : ctx.accounts().get(p.toAccountId());
            if (to == null || (account != null && to.getId().equals(account.getId()))) {
                warnings.add("حساب مقصد انتقال مشخص نیست.");
            } else {
                toAccountId = to.getId();
                boolean sameUnit = account != null && account.getCommodityId().equals(to.getCommodityId());
                if (sameUnit) {
                    toAmount = amount;
                } else if (p.toAmount() != null && p.toAmount().signum() > 0 && p.toAmount().compareTo(MAX_AMOUNT) < 0) {
                    toAmount = plain(p.toAmount());
                } else {
                    warnings.add("مقداری که به حساب «" + to.getName() + "» رسیده مشخص نیست.");
                }
            }
        } else {
            CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            Category category = p.categoryId() == null ? null : ctx.categories().get(p.categoryId());
            if (category != null && category.getKind() == kind) {
                categoryId = category.getId();
            } else if (p.description() != null) {
                categoryId = merchantRules.suggest(ctx.userId(), p.description())
                        .filter(id -> ctx.categories().containsKey(id) && ctx.categories().get(id).getKind() == kind).orElse(null);
            }
        }

        Confidence confidence = p.confidence() == null ? Confidence.MEDIUM : p.confidence();
        if (confidence != Confidence.HIGH) {
            warnings.add(p.note() != null && !p.note().isBlank() ? p.note().strip() : "مدل از برداشت خود از این تراکنش مطمئن نیست.");
        }
        String description = PersianText.clean(p.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION);
        }
        boolean duplicate = account != null && amount != null && type != TransactionType.TRANSFER && exists(ctx.userId(), account.getId(), date,
                type, amount);
        return new Draft(prefix + ":" + newRef(), type, date, account == null ? null : account.getId(), amount, toAccountId, toAmount, categoryId,
                description, confidence, List.copyOf(warnings), duplicate);
    }

    /** {@code amount × scale}, converted from Rial; null unless positive and plausible. */
    static BigDecimal amount(BigDecimal amount, Scale scale, AmountUnit unit) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        BigDecimal value = amount.multiply(scale == null ? BigDecimal.ONE : scale.factor);
        if (unit == AmountUnit.RIAL) {
            value = value.movePointLeft(1);
        }
        if (value.compareTo(MAX_AMOUNT) >= 0) {
            return null;
        }
        return plain(value);
    }

    /** At most 8 decimals, without trailing zeros or exponent. */
    static BigDecimal plain(BigDecimal value) {
        BigDecimal v = value.setScale(Math.min(Math.max(value.stripTrailingZeros().scale(), 0), 8), RoundingMode.HALF_EVEN).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** Whether a transaction like this was already recorded. */
    boolean exists(long userId, long accountId, LocalDate date, TransactionType type, BigDecimal amount) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = :u AND account_id = :a AND occurred_on = :d
                                       AND type = :t AND amount = :amount)
                        """)
                .param("u", userId).param("a", accountId).param("d", date).param("t", type.name()).param("amount", amount)
                .query(Boolean.class).single();
    }

    public Optional<Account> account(Context ctx, Long id) {
        return Optional.ofNullable(id == null ? null : ctx.accounts().get(id));
    }

    private static String newRef() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
