package ir.karname.ai.tools;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountViews;
import ir.karname.account.AccountViews.AccountView;
import ir.karname.ai.capture.DraftBuilder;
import ir.karname.ai.capture.DraftBuilder.Draft;
import ir.karname.ai.guard.Privacy;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.ToolSpec;
import ir.karname.budget.BudgetService;
import ir.karname.budget.BudgetService.BudgetMonth;
import ir.karname.category.Category;
import ir.karname.category.CategoryService;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.web.ApiException;
import ir.karname.common.web.Messages;
import ir.karname.forecast.ForecastService;
import ir.karname.forecast.ForecastService.Forecast;
import ir.karname.goal.GoalService;
import ir.karname.ledger.AssetClass;
import ir.karname.ledger.CostBasisService;
import ir.karname.ledger.DashboardService;
import ir.karname.ledger.DashboardService.MonthSummary;
import ir.karname.ledger.NetWorthService;
import ir.karname.ledger.NetWorthService.NetWorthView;
import ir.karname.report.ReportService;
import ir.karname.report.ReportService.CategoryLine;
import ir.karname.report.ReportService.CategoryReport;
import ir.karname.transaction.TransactionQueryService;
import ir.karname.transaction.TransactionQueryService.Filter;
import ir.karname.transaction.TransactionQueryService.TransactionPage;
import ir.karname.transaction.TransactionType;
import ir.karname.transaction.TransactionViews.TransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the assistant can look up, all read-only and scoped to one user, plus a calculator and a
 * way to propose transactions for the user to confirm. Figures come from the same services as
 * the app's own screens, so the assistant never computes them itself. Amounts are given with
 * thousands separators and units in the field names; dates are Jalali.
 */
@Component
public class FinanceTools {

    private static final Logger log = LoggerFactory.getLogger(FinanceTools.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final int MAX_DESCRIPTION = 120;
    private static final Map<AssetClass, String> ASSET_CLASSES = Map.of(
            AssetClass.TOMAN, "ریالی (نقد و بانک)", AssetClass.FIAT, "ارز", AssetClass.CRYPTO, "رمزارز", AssetClass.GOLD, "طلا و سکه",
            AssetClass.SECURITY, "سهام و صندوق", AssetClass.PROPERTY, "ملک و خودرو", AssetClass.OTHER, "سایر");
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    /** Who is asking, and what the tools may reveal. */
    public record ToolContext(long userId, LocalDate today, ZoneId zone, boolean shareDescriptions, BigDecimal inflationRate) {
    }

    /** {@code data} is for the user interface (proposed transactions), never sent to the model. */
    public record Outcome(String content, boolean error, String data) {
    }

    private record Result(Object content, Object data) {
    }

    private record Tool(ToolSpec spec, BiFunction<ToolContext, JsonNode, Result> handler) {
    }

    private final NetWorthService netWorth;
    private final DashboardService dashboard;
    private final AccountService accounts;
    private final AccountViews accountViews;
    private final CategoryService categories;
    private final TransactionQueryService transactions;
    private final ReportService reports;
    private final BudgetService budgets;
    private final GoalService goals;
    private final ForecastService forecasts;
    private final CommodityService commodities;
    private final CostBasisService costBasis;
    private final DraftBuilder drafts;
    private final Messages messages;
    /** The application's mapper, for data shown in the user interface (decimals as text). */
    private final JsonMapper appJson;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public FinanceTools(NetWorthService netWorth, DashboardService dashboard, AccountService accounts, AccountViews accountViews,
            CategoryService categories, TransactionQueryService transactions, ReportService reports, BudgetService budgets, GoalService goals,
            ForecastService forecasts, CommodityService commodities, CostBasisService costBasis, DraftBuilder drafts, Messages messages,
            JsonMapper appJson) {
        this.netWorth = netWorth;
        this.dashboard = dashboard;
        this.accounts = accounts;
        this.accountViews = accountViews;
        this.categories = categories;
        this.transactions = transactions;
        this.reports = reports;
        this.budgets = budgets;
        this.goals = goals;
        this.forecasts = forecasts;
        this.commodities = commodities;
        this.costBasis = costBasis;
        this.drafts = drafts;
        this.messages = messages;
        this.appJson = appJson;
        register();
    }

    private void add(String name, String label, String description, JsonSchema input, BiFunction<ToolContext, JsonNode, Result> handler) {
        tools.put(name, new Tool(new ToolSpec(name, description, input), handler));
        LABELS.put(name, label);
    }

    private void register() {
        JsonSchema month = JsonSchema.string("Jalali month as yyyy-mm, e.g. 1405-07 for Mehr 1405");
        add("get_financial_overview", "مرور وضعیت مالی",
                "The user's current financial picture: net worth (in Toman and in the alternative units they track, such as US dollars "
                        + "or grams of gold), assets and liabilities, allocation by asset class, and this month's and last month's income, "
                        + "expenses and savings rate. Start here for general questions.",
                JsonSchema.empty(), this::overview);
        add("list_accounts", "فهرست حساب‌ها",
                "The user's accounts with type, unit, current balance (in the account's unit) and value in Toman. Use the ids for "
                        + "search_transactions and propose_transactions.",
                JsonSchema.object(null).optional("include_archived", JsonSchema.bool("Also list archived accounts")).build(), this::accounts);
        add("list_categories", "فهرست دسته‌ها",
                "The user's income and expense categories with ids and parent categories.", JsonSchema.empty(), this::categories);
        add("search_transactions", "جستجوی تراکنش‌ها",
                "Finds transactions, newest first, with the total income and total expense (in Toman) of all matches. Filters combine. "
                        + "Use it for questions about specific purchases, merchants or periods.",
                JsonSchema.object(null)
                        .optional("from", JsonSchema.string("First day, Jalali yyyy/mm/dd"))
                        .optional("to", JsonSchema.string("Last day, Jalali yyyy/mm/dd"))
                        .optional("type", JsonSchema.enumOf("Only this kind", List.of("INCOME", "EXPENSE", "TRANSFER")))
                        .optional("account_id", JsonSchema.integer("Only this account (from list_accounts)"))
                        .optional("category_id", JsonSchema.integer("Only this category or its subcategories (from list_categories)"))
                        .optional("uncategorized", JsonSchema.bool("Only income and expenses without a category"))
                        .optional("query", JsonSchema.string("Words to find in the description, notes or tags").maxLength(100))
                        .optional("min_amount", JsonSchema.number("Smallest amount, in the account's unit").min(0))
                        .optional("max_amount", JsonSchema.number("Largest amount, in the account's unit").min(0))
                        .optional("limit", JsonSchema.integer("How many to list, 1 to 100 (default 30); totals always cover all matches")
                                .min(1).max(100))
                        .build(),
                this::search);
        add("summarize_by_category", "تفکیک بر اساس دسته",
                "Expenses (or income) of a period by category, largest first, with each category's share, its subcategories, the "
                        + "same-length previous period, and for a single month the average of the three months before. Defaults to "
                        + "this month's expenses.",
                JsonSchema.object(null)
                        .optional("kind", JsonSchema.enumOf("EXPENSE (default) or INCOME", List.of("EXPENSE", "INCOME")))
                        .optional("from_month", month)
                        .optional("to_month", month)
                        .build(),
                this::byCategory);
        add("get_monthly_totals", "روند ماهانه",
                "Income, expenses, net and savings rate per Jalali month for recent months, oldest first; the current month is in "
                        + "progress. Use it for trends and comparisons between months.",
                JsonSchema.object(null).optional("months", JsonSchema.integer("How many months, 1 to 36 (default 12)").min(1).max(36)).build(),
                this::monthly);
        add("get_budget_status", "وضعیت بودجه",
                "Budgets of a Jalali month (default: this month): per category the budget, spending, remainder, share used, status "
                        + "(OK, WARNING from 80%, OVER) and projected month-end spending.",
                JsonSchema.object(null).optional("month", month).build(), this::budget);
        add("get_net_worth_history", "تاریخچه‌ی دارایی",
                "Net worth at the end of each recent Jalali month, in Toman and in the alternative units (US dollars, gold…), oldest "
                        + "first. Shows whether wealth grew in real terms, not only in Toman.",
                JsonSchema.object(null).optional("months", JsonSchema.integer("How many months, 1 to 60 (default 12)").min(1).max(60)).build(),
                this::history);
        add("get_asset_allocation", "ترکیب دارایی‌ها",
                "How assets split by class, and each non-Toman holding (currency, gold, coins, crypto…) with quantity, value, average "
                        + "purchase cost and unrealized and realized gain in Toman.",
                JsonSchema.empty(), this::allocation);
        add("get_goals", "اهداف",
                "The user's savings goals: target, progress, monthly trend, estimated month of reaching it and what is needed per "
                        + "month to reach it by its target date.",
                JsonSchema.empty(), this::goals);
        add("get_obligations", "تعهدات و پیش‌بینی نقدینگی",
                "Expected money movements in the coming days (loan installments, cheques, recurring income and bills, overdue ones "
                        + "included) and the resulting liquid balance (cash, bank and wallet accounts in Toman): lowest point and when.",
                JsonSchema.object(null).optional("days", JsonSchema.integer("How many days ahead, 1 to 180 (default 30)").min(1).max(180)).build(),
                this::obligations);
        add("get_prices", "قیمت‌ها",
                "Latest prices in Toman of currencies, gold, coins, crypto and the user's own assets, with when and where each was "
                        + "recorded and whether it is stale.",
                JsonSchema.object(null).optional("codes", JsonSchema.array("Only these codes, e.g. USD, GOLD18, COIN_EMAMI, USDT",
                        JsonSchema.string(null)).maxItems(30)).build(),
                this::prices);
        add("calculate", "محاسبه",
                "Evaluates arithmetic exactly in decimal: numbers (thousands separators allowed), + - * / ^ (whole exponents), % "
                        + "(percent of: 15% = 0.15) and parentheses. Use it for every sum, difference, ratio or percentage you report "
                        + "that a tool did not give directly.",
                JsonSchema.object(null).required("expression", JsonSchema.string("e.g. (12,500,000 - 9,800,000) / 12,500,000 * 100")
                        .maxLength(Calculator.MAX_LENGTH)).build(),
                this::calculate);
        add("project_savings", "پیش‌بینی پس‌انداز",
                "Projects savings with monthly contributions and compound growth (monthly compounding, contributions at month end): "
                        + "either the amount after a number of months, or how many months until a target. Also gives the value in today's "
                        + "money when the user has set an inflation rate.",
                JsonSchema.object(null)
                        .required("monthly_contribution", JsonSchema.number("Saved every month, in Toman").min(0))
                        .optional("current_amount", JsonSchema.number("Already saved, in Toman (default 0)").min(0))
                        .optional("annual_return_percent", JsonSchema.number("Expected yearly return in percent (default 0)").min(-50).max(500))
                        .optional("months", JsonSchema.integer("Project this many months ahead, 1 to 600").min(1).max(600))
                        .optional("target_amount", JsonSchema.number("Find when savings reach this amount, in Toman").min(1))
                        .build(),
                this::project);
        add("propose_transactions", "پیش‌نویس تراکنش",
                "Prepares transactions the user asked to record. They are shown as drafts for the user to review and confirm; nothing "
                        + "is recorded without the user. Use list_accounts and list_categories for ids.",
                JsonSchema.object(null).required("transactions", JsonSchema.array("The transactions", DraftBuilder.proposalSchema()).maxItems(20))
                        .build(),
                this::propose);
    }

    /** All tools, in a fixed order: they are part of the cached prompt. */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** A Persian label of a tool for the conversation view. */
    public static String label(String name) {
        return LABELS.getOrDefault(name, name);
    }

    /** Runs a call after checking its input against the tool's schema; failures become error results the model can act on. */
    public Outcome run(ToolContext ctx, Part.ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return error("Unknown tool: " + call.name());
        }
        JsonNode input;
        try {
            input = JSON.readTree(call.input() == null || call.input().isBlank() ? "{}" : call.input());
        } catch (JacksonException e) {
            return new Outcome(json(obj("error", "INVALID_JSON", "input", call.input())), true, null);
        }
        input = tool.spec().input().coerce(input);
        List<String> problems = tool.spec().input().validate(input);
        if (!problems.isEmpty()) {
            return new Outcome(json(obj("error", "Invalid input", "problems", problems)), true, null);
        }
        try {
            Result result = tool.handler().apply(ctx, input);
            return new Outcome(json(result.content()), false, result.data() == null ? null : appJson.writeValueAsString(result.data()));
        } catch (ApiException e) {
            return error(messages.get(e.code(), e.args()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Tool {} failed", call.name(), e);
            return error("The tool failed; answer without it or try again.");
        }
    }

    /** A plain-data picture of the user's finances, for models that cannot call tools. */
    public String snapshot(ToolContext ctx) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("overview", overview(ctx, JSON.createObjectNode()).content());
        snapshot.put("accounts", accounts(ctx, JSON.createObjectNode()).content());
        snapshot.put("monthly_totals", monthly(ctx, JSON.createObjectNode().put("months", 6)).content());
        snapshot.put("expenses_this_month_by_category", byCategory(ctx, JSON.createObjectNode()).content());
        snapshot.put("budgets_this_month", budget(ctx, JSON.createObjectNode()).content());
        snapshot.put("goals", goals(ctx, JSON.createObjectNode()).content());
        snapshot.put("next_30_days", obligations(ctx, JSON.createObjectNode()).content());
        return json(snapshot);
    }

    private static Outcome error(String message) {
        return new Outcome(json(obj("error", message)), true, null);
    }

    // ---------------------------------------------------------------- tools

    private Result overview(ToolContext ctx, JsonNode in) {
        NetWorthView nw = netWorth.current(ctx.userId());
        JalaliMonth month = JalaliMonth.from(ctx.today());
        MonthSummary current = dashboard.summary(ctx.userId(), month, ctx.today());
        MonthSummary previous = dashboard.summary(ctx.userId(), month.previous(), null);
        return new Result(obj(
                "today", date(ctx.today()) + " (" + JalaliDate.from(ctx.today()).dayOfWeekName() + ")",
                "net_worth_toman", money(nw.totalToman()),
                "assets_toman", money(nw.assetsToman()),
                "liabilities_toman", money(nw.liabilitiesToman()),
                "net_worth_in_other_units", nw.alternatives().stream()
                        .map(a -> obj("unit", a.nameFa() + " (" + a.unitFa() + ")", "value", quantity(a.value(), 2), "price_stale", flag(a.stale())))
                        .toList(),
                "allocation", nw.allocation().stream()
                        .map(s -> obj("class", ASSET_CLASSES.get(s.assetClass()), "value_toman", money(s.valueToman()), "share", percent(s.share())))
                        .toList(),
                "this_month_so_far", summary(current),
                "last_month", summary(previous),
                "accounts_without_price", nw.unpriced().isEmpty() ? null : nw.unpriced().stream()
                        .map(u -> obj("account", Privacy.mask(u.name()), "unit", u.commodity(), "balance", quantity(u.balance(), 8))).toList()),
                null);
    }

    private static Map<String, Object> summary(MonthSummary s) {
        return obj("month", month(s.month()), "income_toman", money(s.incomeToman()), "expense_toman", money(s.expenseToman()),
                "net_toman", money(s.netToman()), "savings_rate", percent(s.savingsRate()),
                "transactions_without_price", s.unpricedCount() > 0 ? s.unpricedCount() : null);
    }

    private Result accounts(ToolContext ctx, JsonNode in) {
        boolean archived = in.path("include_archived").asBoolean(false);
        List<Account> list = accounts.list(ctx.userId()).stream().filter(a -> archived || !a.isArchived()).toList();
        Map<String, CommodityView> units = units(ctx);
        List<Map<String, Object>> rows = accountViews.views(ctx.userId(), list).stream().map(v -> obj(
                "id", v.id(),
                "name", Privacy.mask(v.name()),
                "type", v.type().name(),
                "unit", unit(units, v.commodity()),
                "balance", quantity(v.balance(), 8),
                "value_toman", money(v.valueToman()),
                "liability", flag(v.liability()),
                "bank", v.bank() == null ? null : v.bank().name(),
                "archived", flag(v.archived()),
                "excluded_from_net_worth", flag(!v.includeInNetWorth()),
                "price_stale", flag(v.priceStale()),
                "no_price", flag(!v.priced()))).toList();
        return new Result(obj("accounts", rows), null);
    }

    private Result categories(ToolContext ctx, JsonNode in) {
        List<Category> list = categories.list(ctx.userId()).stream().filter(c -> !c.isArchived()).toList();
        Map<Long, String> names = list.stream().collect(Collectors.toMap(Category::getId, Category::getName));
        return new Result(obj("categories", list.stream().map(c -> obj("id", c.getId(), "name", c.getName(), "kind", c.getKind().name(),
                "parent", c.getParentId() == null ? null : names.get(c.getParentId()))).toList()), null);
    }

    private Result search(ToolContext ctx, JsonNode in) {
        LocalDate from = date(in, "from");
        LocalDate to = date(in, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' is after 'to'");
        }
        List<TransactionType> types = in.has("type") ? List.of(TransactionType.valueOf(in.get("type").asString()))
                : List.of(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.TRANSFER);
        Filter filter = new Filter(from, to, types, longOrNull(in, "account_id"), longOrNull(in, "category_id"),
                in.path("uncategorized").asBoolean(false), in.hasNonNull("query") ? in.get("query").asString() : null,
                in.hasNonNull("min_amount") ? in.get("min_amount").decimalValue() : null,
                in.hasNonNull("max_amount") ? in.get("max_amount").decimalValue() : null, null);
        int limit = in.path("limit").asInt(30);
        TransactionPage page = transactions.search(ctx.userId(), filter, 0, limit);
        List<Map<String, Object>> rows = page.items().stream().map(t -> transaction(t, ctx.shareDescriptions())).toList();
        return new Result(obj(
                "total_matches", page.total(),
                "income_total_toman", money(page.incomeToman()),
                "expense_total_toman", money(page.expenseToman()),
                "listed", rows.size(),
                "transactions_without_price", page.unpricedCount() > 0 ? page.unpricedCount() : null,
                "descriptions", ctx.shareDescriptions() ? null : "hidden by the user's privacy setting",
                "transactions", rows), null);
    }

    private static Map<String, Object> transaction(TransactionView t, boolean descriptions) {
        String category = t.category() == null ? null
                : t.category().parentName() == null ? t.category().name() : t.category().parentName() + " › " + t.category().name();
        String description = descriptions && t.description() != null ? Privacy.mask(t.description()) : null;
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION) + "…";
        }
        return obj(
                "id", t.id(),
                "date", date(t.date()),
                "type", t.type().name(),
                "amount", quantity(t.amount(), 8),
                "unit", t.account() == null ? null : t.account().commodity(),
                "account", t.account() == null ? null : Privacy.mask(t.account().name()),
                "to_account", t.toAccount() == null ? null : Privacy.mask(t.toAccount().name()),
                "to_amount", t.toAccount() == null ? null : quantity(t.toAmount(), 8),
                "to_unit", t.toAccount() == null ? null : t.toAccount().commodity(),
                "fee", t.fee() == null || t.fee().signum() == 0 ? null : quantity(t.fee(), 8),
                "category", category,
                "description", description);
    }

    private Result byCategory(ToolContext ctx, JsonNode in) {
        ReportService.Kind kind = ReportService.Kind.valueOf(in.path("kind").asString("EXPENSE"));
        JalaliMonth current = JalaliMonth.from(ctx.today());
        JalaliMonth to = month(in, "to_month", current);
        JalaliMonth from = month(in, "from_month", to);
        if (from.compareTo(to) > 0) {
            JalaliMonth swap = from;
            from = to;
            to = swap;
        }
        int span = (int) from.monthsUntil(to) + 1;
        if (span > 24) {
            throw new IllegalArgumentException("a period can be at most 24 months");
        }
        CategoryReport report = reports.categories(ctx.userId(), to, span, kind);
        return new Result(obj(
                "kind", kind.name(),
                "from_month", month(report.fromMonth()),
                "to_month", month(report.toMonth()),
                "total_toman", money(report.totalToman()),
                "previous_period_total_toman", money(report.previousTotalToman()),
                "transactions_without_price", report.unpricedCount() > 0 ? report.unpricedCount() : null,
                "categories", report.items().stream().limit(25).map(line -> line(line, span)).toList()), null);
    }

    private static Map<String, Object> line(CategoryLine line, int span) {
        return obj(
                "id", line.categoryId(),
                "name", line.name() == null ? "بدون دسته" : line.name(),
                "value_toman", money(line.valueToman()),
                "share", percent(line.share()),
                "transactions", line.count(),
                "previous_period_toman", money(line.previousToman()),
                "average_of_previous_3_months_toman", span == 1 ? money(line.averageToman()) : null,
                "subcategories", line.children().isEmpty() ? null : line.children().stream().map(c -> line(c, span)).toList());
    }

    private Result monthly(ToolContext ctx, JsonNode in) {
        int months = in.path("months").asInt(12);
        return new Result(obj("months", reports.monthly(ctx.userId(), months).stream().map(m -> obj(
                "month", month(m.month()),
                "income_toman", money(m.incomeToman()),
                "expense_toman", money(m.expenseToman()),
                "net_toman", money(m.netToman()),
                "savings_rate", percent(m.savingsRate()),
                "in_progress", flag(m.partial()),
                "transactions_without_price", m.unpricedCount() > 0 ? m.unpricedCount() : null)).toList()), null);
    }

    private Result budget(ToolContext ctx, JsonNode in) {
        JalaliMonth month = month(in, "month", JalaliMonth.from(ctx.today()));
        BudgetMonth b = budgets.month(ctx.userId(), month);
        return new Result(obj(
                "month", month(b.month()),
                "days_in_month", b.daysInMonth(),
                "days_elapsed", b.daysElapsed(),
                "total_budget_toman", money(b.totalBudget()),
                "spent_in_budgeted_categories_toman", money(b.totalSpent()),
                "spent_outside_budgets_toman", money(b.unbudgetedSpent()),
                "total_expense_toman", money(b.totalExpense()),
                "budgets", b.items().stream().map(i -> obj(
                        "category_id", i.categoryId(),
                        "category", i.parentName() == null ? i.name() : i.parentName() + " › " + i.name(),
                        "budget_toman", money(i.amount()),
                        "spent_toman", money(i.spent()),
                        "remaining_toman", money(i.remaining()),
                        "used", percent(i.ratio()),
                        "status", i.status().name(),
                        "projected_month_end_toman", money(i.projected()))).toList(),
                "note", b.items().isEmpty() ? "No budgets are set for this month." : null), null);
    }

    private Result history(ToolContext ctx, JsonNode in) {
        int months = in.path("months").asInt(12);
        return new Result(obj("months", netWorth.history(ctx.userId(), months).stream().map(p -> {
            Map<String, Object> units = new LinkedHashMap<>();
            p.alternatives().forEach((code, value) -> units.put(code, quantity(value, 2)));
            return obj(
                    "month", month(p.month()),
                    "date", date(p.date()),
                    "net_worth_toman", money(p.totalToman()),
                    "assets_toman", money(p.assetsToman()),
                    "liabilities_toman", money(p.liabilitiesToman()),
                    "net_worth_in_other_units", units.isEmpty() ? null : units,
                    "accounts_without_price", p.unpricedAccounts() > 0 ? p.unpricedAccounts() : null);
        }).toList()), null);
    }

    private Result allocation(ToolContext ctx, JsonNode in) {
        NetWorthView nw = netWorth.current(ctx.userId());
        Map<String, CommodityView> units = units(ctx);
        List<Account> held = accounts.list(ctx.userId()).stream().filter(a -> !a.isArchived() && a.isIncludeInNetWorth()).toList();
        List<Map<String, Object>> holdings = new ArrayList<>();
        for (AccountView v : accountViews.views(ctx.userId(), held)) {
            if ("IRT".equals(v.commodity()) || v.liability() || v.balance().signum() == 0) {
                continue;
            }
            var basis = costBasis.forAccount(ctx.userId(), v.id()).orElse(null);
            holdings.add(obj(
                    "account", Privacy.mask(v.name()),
                    "unit", unit(units, v.commodity()),
                    "quantity", quantity(v.balance(), 8),
                    "value_toman", money(v.valueToman()),
                    "average_cost_toman", basis == null ? null : money(basis.averageCostToman()),
                    "cost_basis_toman", basis == null ? null : money(basis.costBasisToman()),
                    "unrealized_gain_toman", basis == null ? null : money(basis.unrealizedToman()),
                    "realized_gain_toman", basis == null ? null : money(basis.realizedToman()),
                    "cost_history_incomplete", basis == null ? null : flag(!basis.costComplete()),
                    "price_stale", flag(v.priceStale()),
                    "no_price", flag(!v.priced())));
        }
        return new Result(obj(
                "assets_toman", money(nw.assetsToman()),
                "allocation", nw.allocation().stream()
                        .map(s -> obj("class", ASSET_CLASSES.get(s.assetClass()), "value_toman", money(s.valueToman()), "share", percent(s.share())))
                        .toList(),
                "holdings", holdings), null);
    }

    private Result goals(ToolContext ctx, JsonNode in) {
        Map<String, CommodityView> units = units(ctx);
        return new Result(obj("goals", goals.list(ctx.userId(), false).stream().map(g -> obj(
                "name", Privacy.mask(g.name()),
                "target", quantity(g.targetAmount(), 8),
                "unit", unit(units, g.commodity()),
                "target_date", date(g.targetDate()),
                "current", quantity(g.currentAmount(), 8),
                "current_toman", money(g.currentToman()),
                "progress", percent(g.progress()),
                "remaining", quantity(g.remaining(), 8),
                "achieved", flag(g.achieved()),
                "average_monthly_change", quantity(g.monthlyChange(), 8),
                "estimated_month_reached", g.etaMonth() == null ? null : month(g.etaMonth()),
                "months_to_goal", g.monthsToGoal(),
                "months_left_until_target_date", g.monthsLeft(),
                "needed_per_month", quantity(g.requiredPerMonth(), 8),
                "needed_per_month_toman", money(g.requiredPerMonthToman()),
                "on_track", g.onTrack(),
                "missing_prices", flag(g.missingPrices()))).toList()), null);
    }

    private Result obligations(ToolContext ctx, JsonNode in) {
        int days = in.path("days").asInt(30);
        Forecast f = forecasts.forecast(ctx.userId(), days);
        return new Result(obj(
                "from", date(f.from()),
                "to", date(f.to()),
                "liquid_balance_now_toman", money(f.startBalance()),
                "liquid_balance_at_end_toman", money(f.endBalance()),
                "lowest_balance_toman", money(f.minBalance()),
                "lowest_balance_date", date(f.minDate()),
                "expected_inflow_toman", money(f.inflow()),
                "expected_outflow_toman", money(f.outflow()),
                "events", f.events().stream().limit(60).map(e -> obj(
                        "date", date(e.date()),
                        "kind", e.source().name(),
                        "title", Privacy.mask(e.title()),
                        "amount_toman", money(e.amount()),
                        "overdue", flag(e.overdue()))).toList(),
                "note", "Event amounts are signed: negative is money going out. Liquid balance covers cash, bank and wallet accounts in Toman."),
                null);
    }

    private Result prices(ToolContext ctx, JsonNode in) {
        Set<String> codes = in.has("codes") ? in.get("codes").valueStream().map(n -> n.asString().strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet()) : Set.of();
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm").withZone(ctx.zone());
        return new Result(obj("prices", commodities.list(ctx.userId()).stream()
                .filter(c -> !"IRT".equals(c.code()) && (codes.isEmpty() || codes.contains(c.code())))
                .map(c -> obj(
                        "code", c.code(),
                        "name", c.nameFa(),
                        "unit", c.unitFa(),
                        "price_toman", c.latestPrice() == null ? null : money(c.latestPrice().priceToman()),
                        "recorded_at", c.latestPrice() == null ? null : dateTime(c.latestPrice().pricedAt(), ctx.zone(), time),
                        "source", c.latestPrice() == null ? null : c.latestPrice().source(),
                        "entered_by_user", c.latestPrice() == null ? null : flag(c.latestPrice().personal()),
                        "stale", c.latestPrice() == null ? null : flag(c.latestPrice().stale()),
                        "no_price", flag(c.latestPrice() == null)))
                .toList()), null);
    }

    private Result calculate(ToolContext ctx, JsonNode in) {
        String expression = in.get("expression").asString();
        BigDecimal result = Calculator.evaluate(expression);
        return new Result(obj("expression", expression, "result", result.toPlainString(), "result_formatted", group(result)), null);
    }

    private Result project(ToolContext ctx, JsonNode in) {
        BigDecimal monthly = in.get("monthly_contribution").decimalValue();
        BigDecimal balance = in.hasNonNull("current_amount") ? in.get("current_amount").decimalValue() : BigDecimal.ZERO;
        BigDecimal annual = in.hasNonNull("annual_return_percent") ? in.get("annual_return_percent").decimalValue() : BigDecimal.ZERO;
        Integer months = in.hasNonNull("months") ? in.get("months").asInt() : null;
        BigDecimal target = in.hasNonNull("target_amount") ? in.get("target_amount").decimalValue() : null;
        if (months == null && target == null) {
            throw new IllegalArgumentException("give either months or target_amount");
        }
        BigDecimal start = balance;
        BigDecimal rate = annual.divide(BigDecimal.valueOf(1200), MathContext.DECIMAL128);
        int limit = months != null ? months : 600;
        int month = 0;
        boolean reached = target != null && balance.compareTo(target) >= 0;
        while (month < limit && !reached) {
            balance = balance.multiply(BigDecimal.ONE.add(rate), MathContext.DECIMAL128).add(monthly);
            month++;
            reached = target != null && balance.compareTo(target) >= 0;
        }
        BigDecimal contributions = start.add(monthly.multiply(BigDecimal.valueOf(month)));
        Map<String, Object> out = obj(
                "assumption", "monthly compounding at " + annual.stripTrailingZeros().toPlainString() + "% a year; contributions at month end",
                "months", month,
                "amount_toman", money(balance),
                "contributed_toman", money(contributions),
                "growth_toman", money(balance.subtract(contributions)));
        if (target != null) {
            out.put("target_toman", money(target));
            out.put("target_reached", reached);
            if (reached) {
                out.put("reached_in_month", month(JalaliMonth.from(ctx.today()).plusMonths(month).toString()));
            } else {
                out.put("note", "Not reached within " + limit + " months at this pace.");
            }
        }
        if (ctx.inflationRate() != null && ctx.inflationRate().signum() > 0 && month > 0) {
            double deflator = Math.pow(1 + ctx.inflationRate().doubleValue() / 100, month / 12.0);
            out.put("amount_in_todays_money_toman", money(balance.divide(BigDecimal.valueOf(deflator), MathContext.DECIMAL64)));
            out.put("inflation_assumed", ctx.inflationRate().stripTrailingZeros().toPlainString() + "% a year (the user's setting)");
        }
        return new Result(out, null);
    }

    private Result propose(ToolContext ctx, JsonNode in) {
        DraftBuilder.Context context = drafts.context(ctx.userId());
        List<Draft> list = in.get("transactions").valueStream().map(n -> drafts.build(context, DraftBuilder.proposal(n), "ai")).toList();
        List<Map<String, Object>> shown = list.stream().map(d -> obj(
                "type", d.type().name(),
                "date", date(d.date()),
                "amount", quantity(d.amount(), 8),
                "account", drafts.account(context, d.accountId()).map(a -> Privacy.mask(a.getName())).orElse(null),
                "description", d.description(),
                "warnings", d.warnings().isEmpty() ? null : d.warnings(),
                "possible_duplicate", flag(d.duplicate()))).toList();
        return new Result(obj(
                "drafts_shown_to_user", list.size(),
                "drafts", shown,
                "note", "The drafts are shown to the user as cards to review, edit and confirm; nothing is recorded until they do."), list);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, CommodityView> units(ToolContext ctx) {
        return commodities.list(ctx.userId()).stream().collect(Collectors.toMap(CommodityView::code, Function.identity(), (a, b) -> a));
    }

    private static String unit(Map<String, CommodityView> units, String code) {
        if (code == null) {
            return null;
        }
        CommodityView c = units.get(code);
        return c == null ? code : c.nameFa() + " (" + c.unitFa() + ")";
    }

    /** Builds a JSON object, leaving out nulls to keep results short. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                map.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return map;
    }

    private static Boolean flag(boolean value) {
        return value ? Boolean.TRUE : null;
    }

    static String money(BigDecimal value) {
        return value == null ? null : group(value.setScale(0, RoundingMode.HALF_EVEN));
    }

    static String quantity(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        BigDecimal v = value.setScale(Math.min(Math.max(value.scale(), 0), scale), RoundingMode.HALF_EVEN).stripTrailingZeros();
        return group(v.scale() < 0 ? v.setScale(0) : v);
    }

    static String group(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.########", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format.format(value);
    }

    static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return null;
        }
        BigDecimal p = ratio.movePointRight(2).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros();
        return (p.scale() < 0 ? p.setScale(0) : p).toPlainString() + "%";
    }

    static String date(LocalDate date) {
        return date == null ? null : JalaliDate.from(date).toString();
    }

    private static String dateTime(Instant instant, ZoneId zone, DateTimeFormatter time) {
        return JalaliDate.from(instant.atZone(zone).toLocalDate()) + " " + time.format(instant);
    }

    /** A month as "1405-07 (مهر ۱۴۰۵)". */
    static String month(String canonical) {
        try {
            return canonical + " (" + JalaliMonth.parse(canonical).label() + ")";
        } catch (IllegalArgumentException e) {
            return canonical;
        }
    }

    private static LocalDate date(JsonNode in, String field) {
        if (!in.hasNonNull(field)) {
            return null;
        }
        try {
            return JalaliDate.parse(in.get(field).asString().strip().replace('-', '/')).toGregorian();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + field + "' must be a Jalali date as yyyy/mm/dd");
        }
    }

    private static JalaliMonth month(JsonNode in, String field, JalaliMonth fallback) {
        if (!in.hasNonNull(field)) {
            return fallback;
        }
        try {
            return JalaliMonth.parse(in.get(field).asString().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + field + "' must be a Jalali month as yyyy-mm");
        }
    }

    private static Long longOrNull(JsonNode in, String field) {
        return in.hasNonNull(field) ? in.get(field).asLong() : null;
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
