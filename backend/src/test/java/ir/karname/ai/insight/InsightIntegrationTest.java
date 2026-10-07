package ir.karname.ai.insight;

import ir.karname.account.Account;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.fake.FakeLlm;
import ir.karname.ai.provider.AiTask;
import ir.karname.category.MerchantRuleService;
import ir.karname.support.AiTestSupport;
import ir.karname.support.TestUser;
import ir.karname.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsightIntegrationTest extends AiTestSupport {

    @Autowired
    private MerchantRuleService merchantRules;

    private TestUser user;
    private Account bank;

    @BeforeEach
    void setUp() {
        user = createUser("sina");
        bank = bank(user, "ملت", "30000000");
        fakeProvider(true, AiTask.REPORT, AiTask.EXTRACT);
    }

    private static final String REPORT = """
            {"headline":"ماه پرخرجی بود",
             "summary":"هزینه‌ها **۲۵٪** بیشتر از ماه قبل بود.",
             "highlights":[{"title":"پس‌انداز","detail":"نرخ پس‌انداز ۴۵٪ بود.","tone":"POSITIVE"}],
             "suggestions":[{"title":"بودجه‌ی رستوران","detail":"برای رستوران بودجه بگذارید."}]}""";

    @Test
    void writesTheMonthlyReportFromTheAppsFiguresAndNoticesLaterChanges() throws Exception {
        mvc.perform(getAs(user, "/api/v1/ai/reports/1405-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasData").value(false))
                .andExpect(jsonPath("$.report").doesNotExist());
        mvc.perform(postAs(user, "/api/v1/ai/reports/1405-07", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ai.reportNoData"));

        income(user, bank, "40000000", LocalDate.of(2026, 9, 24), category(user, "حقوق و دستمزد"), "حقوق");
        expense(user, bank, "2500000", LocalDate.of(2026, 10, 2), category(user, "رستوران"), "شام");
        fake.then(r -> FakeLlm.text(REPORT));
        mvc.perform(postAs(user, "/api/v1/ai/reports/1405-07", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("1405-07"))
                .andExpect(jsonPath("$.label").value("مهر ۱۴۰۵"))
                .andExpect(jsonPath("$.report.headline").value("ماه پرخرجی بود"))
                .andExpect(jsonPath("$.report.highlights[0].tone").value("POSITIVE"))
                .andExpect(jsonPath("$.report.suggestions[0].title").value("بودجه‌ی رستوران"))
                .andExpect(jsonPath("$.report.model").value(FakeLlm.MODEL))
                .andExpect(jsonPath("$.stale").value(false));

        LlmRequest request = fake.requests().getFirst();
        assertThat(request.output().name()).isEqualTo("monthly_report");
        String data = ((Part.Text) request.messages().getFirst().parts().getLast()).text();
        JsonNode bundle = readJson(data.substring(data.indexOf("<data>") + 6, data.indexOf("</data>")));
        assertThat(bundle.at("/totals/income_toman").asString()).isEqualTo("40,000,000");
        assertThat(bundle.at("/totals/expense_toman").asString()).isEqualTo("2,500,000");
        assertThat(bundle.at("/month_in_progress/days_elapsed").asInt()).isEqualTo(14);
        assertThat(bundle.at("/expenses_by_category/0/name").asString()).isEqualTo("رستوران و کافه");
        assertThat(bundle.at("/net_worth/end_of_month_toman").asString()).isEqualTo("67,500,000");
        assertThat(bundle.has("next_30_days")).isTrue();

        expense(user, bank, "1000000", LocalDate.of(2026, 10, 4), null, "خرید");
        mvc.perform(getAs(user, "/api/v1/ai/reports/1405-07"))
                .andExpect(jsonPath("$.report.headline").value("ماه پرخرجی بود"))
                .andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    void reportsOnlyPastAndCurrentMonths() throws Exception {
        mvc.perform(getAs(user, "/api/v1/ai/reports/1405-08")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ai.invalidMonth"));
        mvc.perform(getAs(user, "/api/v1/ai/reports/next")).andExpect(status().isBadRequest());
        mvc.perform(getAs(user, "/api/v1/ai/reports/1402-01")).andExpect(status().isBadRequest());
        mvc.perform(getAs(user, "/api/v1/ai/reports/1405-06")).andExpect(status().isOk());
    }

    @Test
    void suggestsCategoriesAndTeachesTheMerchantRules() throws Exception {
        Transaction dinner = expense(user, bank, "2500000", LocalDate.of(2026, 10, 2), null, "رستوران شاندیز");
        Transaction salary = income(user, bank, "40000000", LocalDate.of(2026, 10, 1), null, "واریز حقوق");
        expense(user, bank, "500000", LocalDate.of(2026, 10, 3), null, null);
        long restaurant = category(user, "رستوران");
        // n=1 is the newest (dinner); the second suggestion has the wrong kind and is dropped
        fake.then(r -> FakeLlm.text("""
                {"suggestions":[{"n":1,"category_id":%d,"confidence":"HIGH"},{"n":2,"category_id":%d,"confidence":"LOW"}]}"""
                .formatted(restaurant, restaurant)));
        String json = mvc.perform(postAs(user, "/api/v1/ai/categorize", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.considered").value(2))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].transactionId").value(dinner.getId()))
                .andExpect(jsonPath("$.suggestions[0].categoryName").value("رستوران و کافه › رستوران"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain(String.valueOf(salary.getId()) + ",\"type\"");

        mvc.perform(postAs(user, "/api/v1/ai/categorize/apply", Map.of("items", List.of(Map.of("transactionId", dinner.getId(),
                        "categoryId", restaurant)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = ?").param(dinner.getId()).query(Long.class).single())
                .isEqualTo(restaurant);
        assertThat(merchantRules.suggest(user.id(), "رستوران شاندیز")).contains(restaurant);

        // a category of the wrong kind is refused, and nothing is applied
        mvc.perform(postAs(user, "/api/v1/ai/categorize/apply", Map.of("items", List.of(Map.of("transactionId", salary.getId(),
                "categoryId", restaurant))))).andExpect(status().isBadRequest());
    }

    @Test
    void categorizingNeedsDescriptionsToBeShared() throws Exception {
        expense(user, bank, "100000", TODAY, null, "اسنپ");
        jdbc.sql("UPDATE user_settings SET ai_share_descriptions = false WHERE user_id = ?").param(user.id()).update();
        mvc.perform(postAs(user, "/api/v1/ai/categorize", null)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ai.descriptionsDisabled"));
        assertThat(fake.requests()).isEmpty();
    }
}
