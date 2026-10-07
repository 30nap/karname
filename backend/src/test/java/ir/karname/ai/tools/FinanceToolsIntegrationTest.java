package ir.karname.ai.tools;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.ai.llm.Part;
import ir.karname.ai.tools.FinanceTools.Outcome;
import ir.karname.ai.tools.FinanceTools.ToolContext;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceToolsIntegrationTest extends FinanceTestSupport {

    @Autowired
    private FinanceTools tools;

    private TestUser user;
    private Account bank;
    private ToolContext ctx;

    @BeforeEach
    void data() {
        user = createUser("sara");
        bank = bank(user, "ملت ۶۰۳۷-۹۹۷۱-۲۳۴۵-۶۷۸۹", "50000000");
        income(user, bank, "60000000", LocalDate.of(2026, 9, 25), category(user, "حقوق و دستمزد"), "حقوق مهر");
        expense(user, bank, "1800000", LocalDate.of(2026, 10, 1), category(user, "رستوران"), "ناهار با همکاران");
        expense(user, bank, "950000", LocalDate.of(2026, 10, 3), category(user, "تاکسی اینترنتی"), "اسنپ به دفتر 09121234567");
        expense(user, bank, "20000000", LocalDate.of(2026, 9, 1), category(user, "اجاره"), "اجاره شهریور");
        ctx = context(true);
    }

    private ToolContext context(boolean shareDescriptions) {
        return new ToolContext(user.id(), TODAY, ZoneId.of("Asia/Tehran"), shareDescriptions, new BigDecimal("30"));
    }

    private JsonNode run(ToolContext context, String tool, String input) {
        Outcome outcome = tools.run(context, new Part.ToolCall("call_1", tool, input));
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        return readJson(outcome.content());
    }

    @Test
    void offersAFixedSetOfToolsWithClosedSchemas() {
        assertThat(tools.specs()).extracting(s -> s.name()).containsExactly("get_financial_overview", "list_accounts", "list_categories",
                "search_transactions", "summarize_by_category", "get_monthly_totals", "get_budget_status", "get_net_worth_history",
                "get_asset_allocation", "get_goals", "get_obligations", "get_prices", "calculate", "project_savings", "propose_transactions");
        assertThat(tools.specs()).allSatisfy(s -> assertThat(s.input().toMap()).containsEntry("additionalProperties", false));
        assertThat(FinanceTools.label("get_budget_status")).isEqualTo("وضعیت بودجه");
    }

    @Test
    void overviewGivesFiguresFromTheLedger() {
        JsonNode overview = run(ctx, "get_financial_overview", "{}");
        assertThat(overview.get("today").asString()).isEqualTo("1405/07/14 (سه‌شنبه)");
        // 50,000,000 opening + 60,000,000 − 22,750,000
        assertThat(overview.get("net_worth_toman").asString()).isEqualTo("87,250,000");
        assertThat(overview.at("/this_month_so_far/month").asString()).isEqualTo("1405-07 (مهر ۱۴۰۵)");
        assertThat(overview.at("/this_month_so_far/income_toman").asString()).isEqualTo("60,000,000");
        assertThat(overview.at("/this_month_so_far/expense_toman").asString()).isEqualTo("2,750,000");
        assertThat(overview.at("/this_month_so_far/savings_rate").asString()).isEqualTo("95.4%");
        assertThat(overview.at("/last_month/expense_toman").asString()).isEqualTo("20,000,000");
    }

    @Test
    void searchTotalsAllMatchesAndRespectsThePrivacySetting() {
        JsonNode result = run(ctx, "search_transactions", "{\"type\":\"EXPENSE\",\"from\":\"1405/07/01\",\"limit\":1}");
        assertThat(result.get("total_matches").asInt()).isEqualTo(2);
        assertThat(result.get("listed").asInt()).isEqualTo(1);
        assertThat(result.get("expense_total_toman").asString()).isEqualTo("2,750,000");
        JsonNode first = result.at("/transactions/0");
        assertThat(first.get("date").asString()).isEqualTo("1405/07/11");
        assertThat(first.get("category").asString()).contains("تاکسی");
        // phone numbers in descriptions and card numbers in account names are masked
        assertThat(first.get("description").asString()).isEqualTo("اسنپ به دفتر 09******4567");
        assertThat(first.get("account").asString()).isEqualTo("ملت ****-6789");

        JsonNode hidden = run(context(false), "search_transactions", "{\"query\":\"اسنپ\"}");
        assertThat(hidden.get("total_matches").asInt()).isEqualTo(1);
        assertThat(hidden.at("/transactions/0").has("description")).isFalse();
        assertThat(hidden.get("descriptions").asString()).contains("privacy");
    }

    @Test
    void summarizesByCategoryAndMonth() {
        JsonNode month = run(ctx, "summarize_by_category", "{}");
        assertThat(month.get("total_toman").asString()).isEqualTo("2,750,000");
        assertThat(month.at("/categories/0/share").asString()).isEqualTo("65.4%");
        assertThat(month.get("previous_period_total_toman").asString()).isEqualTo("20,000,000");

        JsonNode trend = run(ctx, "get_monthly_totals", "{\"months\":2}");
        assertThat(trend.at("/months/0/month").asString()).startsWith("1405-06");
        assertThat(trend.at("/months/1/in_progress").asBoolean()).isTrue();
    }

    @Test
    void calculatesAndProjectsExactly() {
        assertThat(run(ctx, "calculate", "{\"expression\":\"(60,000,000 - 2,750,000) / 60,000,000 * 100\"}").get("result").asString())
                .isEqualTo("95.41666667");
        JsonNode months = run(ctx, "project_savings", "{\"monthly_contribution\":10000000,\"months\":12}");
        assertThat(months.get("amount_toman").asString()).isEqualTo("120,000,000");
        assertThat(months.get("growth_toman").asString()).isEqualTo("0");
        // with the user's 30% inflation the same sum is worth less in today's money
        assertThat(months.get("amount_in_todays_money_toman").asString()).isEqualTo("92,307,692");

        JsonNode target = run(ctx, "project_savings",
                "{\"monthly_contribution\":\"۵۰۰۰۰۰۰\",\"current_amount\":20000000,\"target_amount\":50000000}");
        assertThat(target.get("months").asInt()).isEqualTo(6);
        assertThat(target.get("target_reached").asBoolean()).isTrue();
        assertThat(target.get("reached_in_month").asString()).startsWith("1406-01");
    }

    @Test
    void rejectsInvalidInputWithReasonsTheModelCanFix() {
        Outcome bad = tools.run(ctx, new Part.ToolCall("c", "search_transactions", "{\"limit\":500,\"colour\":\"red\"}"));
        assertThat(bad.error()).isTrue();
        assertThat(readJson(bad.content()).get("problems")).hasSize(2);
        Outcome notJson = tools.run(ctx, new Part.ToolCall("c", "calculate", "{\"expression\": "));
        assertThat(notJson.error()).isTrue();
        assertThat(notJson.content()).contains("INVALID_JSON");
        Outcome badDate = tools.run(ctx, new Part.ToolCall("c", "search_transactions", "{\"from\":\"yesterday\"}"));
        assertThat(badDate.content()).contains("Jalali date");
        assertThat(tools.run(ctx, new Part.ToolCall("c", "drop_tables", "{}")).error()).isTrue();
        Outcome divide = tools.run(ctx, new Part.ToolCall("c", "calculate", "{\"expression\":\"1/0\"}"));
        assertThat(divide.content()).contains("division by zero");
    }

    @Test
    void seesOnlyTheUsersOwnData() {
        TestUser other = createUser("other");
        Account foreign = bank(other, "حساب دیگری", "999000000");
        expense(other, foreign, "5000000", TODAY, null, "خرید محرمانه");
        JsonNode search = run(ctx, "search_transactions", "{\"account_id\":" + foreign.getId() + "}");
        assertThat(search.get("total_matches").asInt()).isZero();
        assertThat(run(ctx, "list_accounts", "{}").get("accounts")).hasSize(1);

        Outcome proposed = tools.run(ctx, new Part.ToolCall("c", "propose_transactions", """
                {"transactions":[{"type":"EXPENSE","amount":180,"scale":"THOUSAND","unit":"TOMAN","date":"1405/07/14",
                  "account_id":%d,"description":"ناهار","confidence":"HIGH"}]}""".formatted(foreign.getId())));
        JsonNode draft = readJson(proposed.data()).get(0);
        assertThat(draft.get("accountId").asLong()).isEqualTo(bank.getId());
    }

    @Test
    void proposedTransactionsBecomeCheckedDrafts() {
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", null);
        globalPrice("USD", "100000", Instant.parse("2026-10-05T08:00:00Z"));
        Outcome outcome = tools.run(ctx, new Part.ToolCall("call_9", "propose_transactions", """
                {"transactions":[
                  {"type":"EXPENSE","amount":"۱۸۰","scale":"THOUSAND","unit":"TOMAN","date":"1405/07/13","category_id":%d,
                   "description":"ناهار","confidence":"HIGH"},
                  {"type":"EXPENSE","amount":9500000,"scale":"ONE","unit":"RIAL","date":"1405/07/11","description":"اسنپ به دفتر","confidence":"HIGH"},
                  {"type":"TRANSFER","amount":10,"scale":"MILLION","unit":"TOMAN","date":"1405/07/14","account_id":%d,
                   "to_account_id":%d,"to_amount":100,"description":"خرید دلار","confidence":"HIGH"},
                  {"type":"EXPENSE","amount":800,"scale":"MILLION","unit":"TOMAN","date":"1405/07/14","description":"ماشین",
                   "confidence":"LOW","note":"مشخص نیست ۸۰۰ تومن یعنی ۸۰۰ هزار یا ۸۰۰ میلیون"}
                ]}""".formatted(category(user, "رستوران"), bank.getId(), dollars.getId())));
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        JsonNode drafts = readJson(outcome.data());
        assertThat(drafts).hasSize(4);

        JsonNode lunch = drafts.get(0);
        assertThat(lunch.get("amount").asString()).isEqualTo("180000");
        assertThat(lunch.get("date").asString()).isEqualTo("2026-10-05");
        assertThat(lunch.get("accountId").asLong()).isEqualTo(bank.getId());
        assertThat(lunch.get("ref").asString()).matches("ai:[0-9a-f]{32}");
        assertThat(lunch.get("duplicate").asBoolean()).isFalse();

        // Rial converted in code, the category learned from earlier choices, and the existing ride flagged
        JsonNode ride = drafts.get(1);
        assertThat(ride.get("amount").asString()).isEqualTo("950000");
        assertThat(ride.get("categoryId").asLong()).isEqualTo(category(user, "تاکسی اینترنتی"));
        assertThat(ride.get("duplicate").asBoolean()).isTrue();

        JsonNode exchange = drafts.get(2);
        assertThat(exchange.get("toAmount").asString()).isEqualTo("100");
        assertThat(exchange.get("warnings")).isEmpty();

        JsonNode car = drafts.get(3);
        assertThat(car.get("confidence").asString()).isEqualTo("LOW");
        assertThat(car.get("warnings").get(0).asString()).contains("۸۰۰ تومن");

        JsonNode content = readJson(outcome.content());
        assertThat(content.get("drafts_shown_to_user").asInt()).isEqualTo(4);
        assertThat(content.get("note").asString()).contains("nothing is recorded");
    }
}
