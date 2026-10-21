package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.commodity.CommodityRepository;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.recurring.Frequency;
import ir.karname.recurring.RecurringMode;
import ir.karname.recurring.RecurringService;
import ir.karname.recurring.RecurringService.RuleRequest;
import ir.karname.recurring.RecurringService.RuleView;
import ir.karname.transaction.TransactionType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** A rule for income, a bill or a transfer that repeats: posted on its dates automatically, or only reminded. */
@Component
class RecurringProposals implements ProposalKind<RuleRequest> {

    private final RecurringService recurring;
    private final AccountService accounts;
    private final CategoryService categories;
    private final CommodityRepository commodities;
    private final Clock clock;

    RecurringProposals(RecurringService recurring, AccountService accounts, CategoryService categories, CommodityRepository commodities,
            Clock clock) {
        this.recurring = recurring;
        this.accounts = accounts;
        this.categories = categories;
        this.commodities = commodities;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return "RECURRING";
    }

    @Override
    public String tool() {
        return "propose_recurring";
    }

    @Override
    public String label() {
        return "پیشنهاد تراکنش تکراری";
    }

    @Override
    public String description() {
        return "Proposes a recurring transaction for the user to confirm: a salary, rent, a subscription or bill, or a regular transfer "
                + "(e.g. monthly savings). By default the user is reminded on each date and records it with one click; automatic "
                + "posting only when the user asked for it. Loan installments come from propose_loan instead.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("name", JsonSchema.string("Short name, e.g. «حقوق» or «اجاره خانه»").maxLength(100))
                .required("type", JsonSchema.enumOf("INCOME, EXPENSE, or TRANSFER between the user's accounts",
                        List.of("INCOME", "EXPENSE", "TRANSFER")));
        b.required("amount", JsonSchema.number("The amount each time, as the user said it before any scale word").min(0))
                .optional("amount_scale", JsonSchema.enumOf("Scale word: ONE (default), THOUSAND, MILLION or BILLION",
                        List.of("ONE", "THOUSAND", "MILLION", "BILLION")))
                .optional("amount_unit", JsonSchema.enumOf("TOMAN (default) or RIAL; ACCOUNT when the account is kept in another unit "
                        + "and the amount is in that unit", List.of("TOMAN", "RIAL", "ACCOUNT")));
        return b.optional("account", Inputs.reference("The account money leaves (EXPENSE, TRANSFER) or enters (INCOME); default the "
                        + "everyday account"))
                .optional("to_account", Inputs.reference("TRANSFER: the receiving account"))
                .optional("to_amount", JsonSchema.number("TRANSFER between different units: what the receiving account gets, in its "
                        + "unit (e.g. grams of gold), as a final number").min(0))
                .optional("category", Inputs.reference("INCOME or EXPENSE: the category"))
                .required("frequency", JsonSchema.enumOf("How often", List.of("WEEKLY", "MONTHLY", "YEARLY")))
                .optional("interval", JsonSchema.integer("Every how many weeks, months or years; default 1").min(1).max(12))
                .optional("day_of_month", JsonSchema.integer("MONTHLY or YEARLY: Jalali day of the month, 1 to 31 (clamped in shorter "
                        + "months); default the start date's day").min(1).max(31))
                .optional("day_of_week", JsonSchema.integer("WEEKLY: 0 = Saturday … 6 = Friday; default the start date's weekday")
                        .min(0).max(6))
                .optional("month_of_year", JsonSchema.integer("YEARLY: Jalali month 1 to 12; default the start date's month").min(1).max(12))
                .optional("start_date", Inputs.date("First date it can occur; default today"))
                .optional("end_date", Inputs.date("Last date, if it ends"))
                .optional("automatic", JsonSchema.bool("True only when the user asked for it to be recorded automatically on each date; "
                        + "default false (a reminder each time)"))
                .optional("description", JsonSchema.string("Description on each transaction; default the name").maxLength(300))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<RuleRequest> payloadType() {
        return RuleRequest.class;
    }

    @Override
    public List<Prepared<RuleRequest>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        TransactionType type = Inputs.enumValue(in, "type", TransactionType.class);
        Account account = lookup.account("account", Inputs.text(in, "account"), a -> true, "one of the user's accounts");
        if (account == null) {
            account = lookup.everyday().orElseThrow(() -> new IllegalArgumentException("account: the user has no Toman bank, wallet or "
                    + "cash account; say which account"));
            warnings.add("حساب مشخص نبود؛ «" + account.getName() + "» در نظر گرفته شد.");
        }
        boolean inUnit = "ACCOUNT".equals(Inputs.text(in, "amount_unit"));
        if (!"IRT".equals(lookup.unitOf(account)) && !inUnit) {
            throw new IllegalArgumentException("amount: «" + account.getName() + "» is kept in " + lookup.unitOf(account) + "; give the amount "
                    + "in that unit with amount_unit ACCOUNT");
        }
        BigDecimal amount = inUnit ? Inputs.quantity(in, "amount") : Inputs.toman(in, "amount");
        Long toAccountId = null;
        BigDecimal toAmount = null;
        Long categoryId = null;
        if (type == TransactionType.TRANSFER) {
            Account source = account;
            Account to = lookup.account("to_account", Inputs.text(in, "to_account"), a -> !a.equals(source), "another of the user's accounts");
            if (to == null) {
                throw new IllegalArgumentException("to_account: a TRANSFER needs the receiving account");
            }
            toAccountId = to.getId();
            if (!lookup.unitOf(to).equals(lookup.unitOf(account))) {
                toAmount = in.hasNonNull("to_amount") ? Inputs.quantity(in, "to_amount") : null;
                if (toAmount == null) {
                    throw new IllegalArgumentException("to_amount: «" + to.getName() + "» is kept in " + lookup.unitOf(to)
                            + "; say how much of it each transfer buys");
                }
            }
        } else {
            Category category = lookup.category("category", Inputs.text(in, "category"),
                    type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE);
            categoryId = category == null ? null : category.getId();
        }
        LocalDate start = Inputs.date(in, "start_date");
        boolean automatic = Inputs.flag(in, "automatic");
        return List.of(Prepared.of(new RuleRequest(Inputs.text(in, "name"), type, account.getId(), toAccountId, amount, toAmount, categoryId,
                Inputs.text(in, "description"), Inputs.enumValue(in, "frequency", Frequency.class), Inputs.integer(in, "interval"),
                Inputs.integer(in, "day_of_month"), Inputs.integer(in, "day_of_week"), Inputs.integer(in, "month_of_year"),
                start == null ? request.today() : start, Inputs.date(in, "end_date"), automatic ? RecurringMode.AUTO : RecurringMode.REMIND,
                true), warnings));
    }

    @Override
    public Object execute(long userId, RuleRequest payload) {
        return recurring.create(userId, payload);
    }

    @Override
    public Card card(long userId, RuleRequest p, Object result) {
        RuleView v = (RuleView) result;
        Card.Builder card = Card.builder("«" + p.name() + "»، " + Labels.frequency(v.frequency(), v.interval()))
                .text("نوع", Labels.transactionType(p.type()));
        BigDecimal amount = v.amount();
        Account account = accounts.require(userId, p.accountId());
        String code = unitOf(account);
        if ("IRT".equals(code)) {
            card.money("مبلغ هر بار", amount, true);
        } else {
            card.quantity("مبلغ هر بار", amount, code, true);
        }
        card.text(p.type() == TransactionType.INCOME ? "به حساب" : "از حساب", account.getName());
        if (v.toAccountId() != null) {
            Account to = accounts.require(userId, v.toAccountId());
            card.text("به حساب", to.getName());
            card.quantity("مقدار دریافتی", v.toAmount(), unitOf(to));
        }
        card.text("دسته", v.categoryId() == null ? null : categories.require(userId, v.categoryId()).getName())
                .text("تکرار", schedule(v))
                .date("از تاریخ", v.startDate())
                .date("تا تاریخ", v.endDate())
                .text("ثبت", v.mode() == RecurringMode.AUTO ? "خودکار در هر سررسید" : "با یادآوری و تأیید شما");
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> next = recurring.occurrences(userId, today, today.plusDays(400)).stream()
                .filter(o -> o.ruleId() == v.id()).map(o -> o.date()).limit(3).toList();
        if (!next.isEmpty()) {
            card.text("سه نوبت بعدی", next.stream().map(d -> JalaliDate.from(d).toDisplayString()).collect(Collectors.joining("، ")));
        }
        if (v.mode() == RecurringMode.AUTO) {
            card.warning("در هر سررسید بدون تأیید دوباره‌ی شما ثبت می‌شود.");
        } else {
            card.effect("در هر سررسید یادآوری می‌شود و با یک کلیک ثبت می‌شود.");
        }
        return card.build();
    }

    private String unitOf(Account account) {
        return commodities.findById(account.getCommodityId()).map(c -> c.getCode()).orElse("IRT");
    }

    private static String schedule(RuleView v) {
        String every = Labels.frequency(v.frequency(), v.interval());
        return switch (v.frequency()) {
            case WEEKLY -> every + "، " + Labels.weekday(v.dayOfWeek());
            case MONTHLY -> every + "، روز " + v.dayOfMonth();
            case YEARLY -> every + "، " + v.dayOfMonth() + " " + JalaliDate.monthName(v.monthOfYear());
        };
    }

    @Override
    public String link(RuleRequest payload, Object result) {
        return "/recurring";
    }
}
