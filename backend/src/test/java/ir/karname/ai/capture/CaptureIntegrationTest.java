package ir.karname.ai.capture;

import ir.karname.account.Account;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.account.Bank;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.fake.FakeLlm;
import ir.karname.ai.provider.AiTask;
import ir.karname.support.AiTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaptureIntegrationTest extends AiTestSupport {

    private TestUser user;
    private Account mellat;
    private Account saman;

    @BeforeEach
    void setUp() {
        user = createUser("sina");
        mellat = accountService.create(user.id(), new AccountRequest("ملت ۶۰۳۷۹۹۷۱۲۳۴۵۴۳۲۱", AccountType.BANK, "IRT", Bank.MELLAT, List.of("4321"),
                null, null, null, null, new BigDecimal("30000000"), TODAY.minusMonths(1)));
        saman = accountService.create(user.id(), new AccountRequest("سامان", AccountType.BANK, "IRT", Bank.SAMAN, List.of("5678"),
                null, null, null, null, null, null));
        fakeProvider(true, AiTask.EXTRACT);
    }

    private static String text(LlmRequest request) {
        return request.messages().getFirst().parts().stream().map(p -> switch (p) {
            case Part.Text t -> t.text();
            case Part.Context c -> c.text();
            default -> "";
        }).collect(Collectors.joining("\n"));
    }

    @Test
    void quickAddTurnsANoteIntoDrafts() throws Exception {
        fake.then(r -> FakeLlm.text("""
                {"transactions":[
                  {"type":"EXPENSE","amount":180,"scale":"THOUSAND","unit":"TOMAN","date":"1405/07/13","description":"ناهار","confidence":"HIGH"},
                  {"type":"EXPENSE","amount":95,"scale":"THOUSAND","unit":"TOMAN","date":"1405/07/13","account_id":%d,
                   "description":"اسنپ","confidence":"MEDIUM","note":"مقیاس «تومن» حدس زده شد"}]}""".formatted(saman.getId())));
        mvc.perform(postAs(user, "/api/v1/ai/quick-add", Map.of("text", "دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن از سامان")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts.length()").value(2))
                .andExpect(jsonPath("$.drafts[0].amount").value("180000"))
                .andExpect(jsonPath("$.drafts[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$.drafts[0].warnings[0]").value("حساب مشخص نبود؛ «ملت ۶۰۳۷۹۹۷۱۲۳۴۵۴۳۲۱» انتخاب شد."))
                .andExpect(jsonPath("$.drafts[1].amount").value("95000"))
                .andExpect(jsonPath("$.drafts[1].accountId").value(saman.getId()))
                .andExpect(jsonPath("$.drafts[1].confidence").value("MEDIUM"))
                .andExpect(jsonPath("$.drafts[1].warnings[0]").value("مقیاس «تومن» حدس زده شد"))
                .andExpect(jsonPath("$.drafts[1].recorded").value(false));

        LlmRequest request = fake.requests().getFirst();
        assertThat(request.output().name()).isEqualTo("transactions");
        assertThat(request.tools()).isEmpty();
        String sent = text(request);
        assertThat(sent).contains("امروز: سه‌شنبه ۱۴ مهر ۱۴۰۵").contains("<note>").contains("\"bank\":\"سامان\"")
                // the card number in an account name never leaves
                .contains("ملت ****-4321").doesNotContain("6037997123454321");
        assertThat(jdbc.sql("SELECT task || ':' || outcome FROM ai_usage").query(String.class).single()).isEqualTo("QUICK_ADD:OK");
    }

    @Test
    void retriesOnceWhenTheReplyIsNotUsable() throws Exception {
        fake.then(r -> FakeLlm.text("حتماً! این هم تراکنش‌ها: «ناهار»"))
                .then(r -> FakeLlm.text("{\"transactions\":[]}"));
        mvc.perform(postAs(user, "/api/v1/ai/quick-add", Map.of("text", "سلام")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts.length()").value(0));
        List<LlmMessage> retry = fake.requests().get(1).messages();
        assertThat(retry).hasSize(3);
        assertThat(((Part.Text) retry.getLast().parts().getFirst()).text()).contains("not a JSON object");

        fake.then(r -> FakeLlm.text("{\"items\":1}")).then(r -> FakeLlm.text("not json"));
        mvc.perform(postAs(user, "/api/v1/ai/quick-add", Map.of("text", "سلام")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ai.invalidOutput"));
        mvc.perform(postAs(user, "/api/v1/ai/quick-add", Map.of("text", " "))).andExpect(status().isBadRequest());
    }

    private static final String SMS = """
            بانک ملت
            برداشت:1,250,000
            حساب:***4321
            مانده:12,345,678
            07/13-12:30

            رمز پویا: 123456

            بانک سامان
            واریز 50,000,000 ریال
            کارت 6219861012345678
            1405/07/10
            """;

    private void smsReply() {
        fake.then(r -> FakeLlm.text("""
                {"transactions":[
                  {"message":1,"type":"EXPENSE","amount":1250000,"unit":"RIAL","date":"1405/07/13","description":"برداشت","confidence":"HIGH"},
                  {"message":3,"type":"INCOME","amount":50000000,"unit":"RIAL","date":"1405/07/10","description":"واریز","confidence":"HIGH"}],
                 "ignored":[{"message":2,"reason":"رمز یک‌بار مصرف"}]}"""));
    }

    @Test
    void bankSmsBecomeDraftsOnTheRightAccountsAndAreRecordedOnce() throws Exception {
        smsReply();
        String json = mvc.perform(postAs(user, "/api/v1/ai/sms", Map.of("text", SMS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").value(3))
                .andExpect(jsonPath("$.ignored[0].message").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = readJson(json);
        JsonNode withdrawal = result.at("/drafts/0/draft");
        assertThat(withdrawal.get("accountId").asLong()).isEqualTo(mellat.getId());
        assertThat(withdrawal.get("amount").asString()).isEqualTo("125000");
        assertThat(withdrawal.get("ref").asString()).matches("sms:[0-9a-f]{32}");
        JsonNode deposit = result.at("/drafts/1/draft");
        assertThat(deposit.get("accountId").asLong()).isEqualTo(saman.getId());
        assertThat(deposit.get("amount").asString()).isEqualTo("5000000");
        assertThat(deposit.get("type").asString()).isEqualTo("INCOME");

        String sent = text(fake.requests().getFirst());
        assertThat(sent).contains("<sms number=\"3\">").contains("****-5678").doesNotContain("6219861012345678");

        List<Map<String, Object>> drafts = List.of(draft(withdrawal), draft(deposit));
        mvc.perform(postAs(user, "/api/v1/ai/drafts/commit", Map.of("drafts", drafts)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(0));
        mvc.perform(postAs(user, "/api/v1/ai/drafts/commit", Map.of("drafts", drafts)))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE user_id = ? AND source = 'SMS'").param(user.id()).query(Integer.class).single())
                .isEqualTo(2);

        // the same messages pasted again are recognised
        smsReply();
        mvc.perform(postAs(user, "/api/v1/ai/sms", Map.of("text", SMS)))
                .andExpect(jsonPath("$.drafts[0].draft.recorded").value(true))
                .andExpect(jsonPath("$.drafts[0].draft.duplicate").value(true));
    }

    private static Map<String, Object> draft(JsonNode d) {
        return Map.of("ref", d.get("ref").asString(), "type", d.get("type").asString(), "date", d.get("date").asString(),
                "accountId", d.get("accountId").asLong(), "amount", d.get("amount").asString(), "description", d.get("description").asString());
    }

    @Test
    void recordingIsAllOrNothingAndOnlyOnTheUsersAccounts() throws Exception {
        TestUser other = createUser("other");
        Account foreign = bank(other, "دیگری", "1000");
        Map<String, Object> good = Map.of("ref", "ai:" + "a".repeat(32), "type", "EXPENSE", "date", "2026-10-06",
                "accountId", mellat.getId(), "amount", "100000", "description", "خرید");
        Map<String, Object> bad = Map.of("ref", "ai:" + "b".repeat(32), "type", "EXPENSE", "date", "2026-10-06",
                "accountId", foreign.getId(), "amount", "100000", "description", "خرید");
        mvc.perform(postAs(user, "/api/v1/ai/drafts/commit", Map.of("drafts", List.of(good, bad)))).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE source = 'AI'").query(Integer.class).single()).isZero();

        Map<String, Object> forged = Map.of("ref", "loan:1:1:p", "type", "EXPENSE", "date", "2026-10-06", "accountId", mellat.getId(),
                "amount", "100000");
        mvc.perform(postAs(user, "/api/v1/ai/drafts/commit", Map.of("drafts", List.of(forged))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ai.invalidDrafts"));
        mvc.perform(postAs(user, "/api/v1/ai/drafts/commit", Map.of("drafts", List.of(good)))).andExpect(jsonPath("$.created").value(1));
        assertThat(jdbc.sql("SELECT source FROM transactions WHERE external_ref = ?").param("ai:" + "a".repeat(32)).query(String.class).single())
                .isEqualTo("AI");
    }

    @Test
    void matchesAnSmsToAnAccountByCardEndingOrBankName() {
        DraftBuilder.Context ctx = builder.context(user.id());
        assertThat(CaptureService.accountFor(ctx, "خرید با کارت 6037-99**-****-4321")).isEqualTo(mellat.getId());
        assertThat(CaptureService.accountFor(ctx, "بانک سامان\nانتقال")).isEqualTo(saman.getId());
        assertThat(CaptureService.accountFor(ctx, "بانک ملت | مانده ۱۲٬۰۰۰")).isEqualTo(mellat.getId());
        assertThat(CaptureService.accountFor(ctx, "مبلغ 1,234,321 ریال")).isNull();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private DraftBuilder builder;
}
