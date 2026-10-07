package ir.karname.ai.chat;

import ir.karname.account.Account;
import ir.karname.ai.chat.ChatService.Turn;
import ir.karname.ai.chat.ChatService.View;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.fake.FakeLlm;
import ir.karname.ai.provider.AiTask;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.common.web.ApiException;
import ir.karname.support.AiTestSupport;
import ir.karname.support.TestUser;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionSource;
import ir.karname.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatIntegrationTest extends AiTestSupport {

    @Autowired
    private ChatService chat;

    @Autowired
    private AiUsageService usage;

    private TestUser user;
    private Account bank;

    @BeforeEach
    void setUp() {
        user = createUser("sina");
        bank = bank(user, "ملت", "40000000");
        expense(user, bank, "2500000", LocalDate.of(2026, 10, 2), category(user, "رستوران"), "شام");
    }

    private RecordingSink turn(Long conversationId, String text) {
        Turn turn = chat.prepare(user.id(), conversationId, text);
        RecordingSink sink = new RecordingSink();
        chat.run(turn, sink);
        return sink;
    }

    private static long conversationId(RecordingSink sink) {
        return ((Number) sink.last("start").get("conversationId")).longValue();
    }

    @Test
    void answersWithToolsAndKeepsTheConversation() {
        fakeProvider(true, AiTask.CHAT);
        fake.then(FakeLlm.toolCalls(FakeLlm.call("get_financial_overview", "{}")))
                .then(r -> FakeLlm.text("دارایی خالص شما ۳۷٬۵۰۰٬۰۰۰ تومان است."));

        RecordingSink sink = turn(null, "دارایی خالصم چقدر است؟");
        assertThat(sink.names().subList(0, 3)).containsExactly("start", "tool", "tool");
        assertThat(sink.names().getLast()).isEqualTo("done");
        assertThat(sink.text()).isEqualTo("دارایی خالص شما ۳۷٬۵۰۰٬۰۰۰ تومان است.");
        Map<?, ?> running = (Map<?, ?>) sink.events.get(1).data();
        assertThat(running.get("name")).isEqualTo("get_financial_overview");
        assertThat(running.get("label")).isEqualTo("مرور وضعیت مالی");
        assertThat(running.get("status")).isEqualTo("running");
        assertThat(((Map<?, ?>) sink.events.get(2).data()).get("status")).isEqualTo("done");
        assertThat(sink.closed).isTrue();

        // the second request carried the tool result, computed by the app, not the model
        LlmRequest second = fake.requests().get(1);
        assertThat(second.tools()).hasSize(15);
        assertThat(second.system()).contains("دستیار کارنامه");
        LlmMessage.User results = (LlmMessage.User) second.messages().getLast();
        Part.ToolResult result = (Part.ToolResult) results.parts().getFirst();
        assertThat(result.error()).isFalse();
        assertThat(readJson(result.content()).get("net_worth_toman").asString()).isEqualTo("37,500,000");
        LlmMessage.User question = (LlmMessage.User) second.messages().getFirst();
        assertThat(question.parts().getFirst()).isEqualTo(new Part.Context("امروز: سه‌شنبه ۱۴ مهر ۱۴۰۵ (1405/07/14)"));

        // the next turn replays everything before it, unchanged
        long id = conversationId(sink);
        fake.then(r -> FakeLlm.text("خواهش می‌کنم."));
        turn(id, "ممنون");
        LlmRequest third = fake.requests().get(2);
        assertThat(third.messages()).hasSize(5);
        assertThat(third.messages().subList(0, 4)).isEqualTo(List.of(question, second.messages().get(1), results,
                new LlmMessage.Assistant(List.of(new Part.Text("دارایی خالص شما ۳۷٬۵۰۰٬۰۰۰ تومان است.")), null)));

        View view = chat.get(user.id(), id);
        assertThat(view.title()).isEqualTo("دارایی خالصم چقدر است؟");
        assertThat(view.available()).isTrue();
        assertThat(view.items()).extracting(ChatService.Item::kind).containsExactly("user", "tool", "assistant", "user", "assistant");
        assertThat(view.items().get(1).label()).isEqualTo("مرور وضعیت مالی");
        assertThat(usage.quota(user.id()).used()).isEqualTo(2);
    }

    @Test
    void streamsTheReplyAsServerSentEvents() throws Exception {
        fakeProvider(true, AiTask.CHAT);
        fake.then(r -> FakeLlm.text("سلام! چطور کمک کنم؟"));
        MvcResult started = mvc.perform(postAs(user, "/api/v1/ai/chat", Map.of("text", "سلام")))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = awaitBody(started);
        assertThat(started.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(body).contains("event:start").contains("event:text").contains("event:done").contains("سلام! چطور");
    }

    private static String awaitBody(MvcResult result) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("event:done") || body.contains("event:error")) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("stream did not finish: " + result.getResponse().getContentAsString());
    }

    @Test
    void aRefusedTurnIsShownButNotSentAgain() {
        fakeProvider(true, AiTask.CHAT);
        fake.then(r -> FakeLlm.text("سلام"));
        long id = conversationId(turn(null, "سلام"));
        fake.then(FakeLlm.refusal());
        RecordingSink refused = turn(id, "یک سؤال مشکوک");
        assertThat(refused.names()).containsExactly("start", "error");
        assertThat(refused.last("error").get("message").toString()).contains("پاسخ نداد");

        fake.then(r -> FakeLlm.text("بفرمایید"));
        turn(id, "سؤال بعدی");
        List<LlmMessage> sent = fake.requests().getLast().messages();
        assertThat(sent).hasSize(3);
        assertThat(((Part.Text) sent.getLast().parts().getLast()).text()).isEqualTo("سؤال بعدی");

        View view = chat.get(user.id(), id);
        assertThat(view.items()).extracting(ChatService.Item::kind)
                .containsExactly("user", "assistant", "user", "error", "user", "assistant");
    }

    @Test
    void providerFailuresEndTheTurnWithAPersianMessage() {
        fakeProvider(true, AiTask.CHAT);
        fake.thenFail(LlmException.Kind.UNAVAILABLE);
        RecordingSink sink = turn(null, "گزارش بده");
        assertThat(sink.names()).containsExactly("start", "error");
        assertThat(sink.last("error").get("message")).isEqualTo("سرویس هوش مصنوعی موقتاً در دسترس نیست؛ کمی بعد دوباره امتحان کنید.");
        assertThat(jdbc.sql("SELECT outcome FROM ai_usage WHERE user_id = ?").param(user.id()).query(String.class).single()).isEqualTo("ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_messages WHERE excluded").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void proposedTransactionsAreShownAsDraftsAndKnowWhenRecorded() {
        fakeProvider(true, AiTask.CHAT);
        fake.then(FakeLlm.toolCalls(FakeLlm.call("propose_transactions", """
                        {"transactions":[{"type":"EXPENSE","amount":180,"scale":"THOUSAND","unit":"TOMAN","date":"1405/07/13",
                          "description":"ناهار","confidence":"HIGH"}]}""")))
                .then(r -> FakeLlm.text("پیش‌نویس را بررسی و تأیید کنید."));
        RecordingSink sink = turn(null, "دیروز ناهار ۱۸۰ تومن");
        assertThat(sink.names()).contains("drafts");
        @SuppressWarnings("unchecked")
        List<ir.karname.ai.capture.DraftView> drafts = (List<ir.karname.ai.capture.DraftView>) sink.last("drafts").get("drafts");
        assertThat(drafts).singleElement().satisfies(d -> {
            assertThat(d.amount()).isEqualByComparingTo("180000");
            assertThat(d.date()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(d.recorded()).isFalse();
        });

        long id = conversationId(sink);
        String ref = drafts.getFirst().ref();
        transactionService.create(user.id(), new TransactionRequest(TransactionType.EXPENSE, LocalDate.of(2026, 10, 5), bank.getId(),
                new BigDecimal("180000"), null, null, null, null, "ناهار", null, List.of()), TransactionSource.AI, ref);
        View view = chat.get(user.id(), id);
        ChatService.Item item = view.items().stream().filter(i -> i.kind().equals("drafts")).findFirst().orElseThrow();
        assertThat(item.drafts()).singleElement().satisfies(d -> assertThat(d.recorded()).isTrue());
    }

    @Test
    void modelsWithoutToolsGetASnapshotWithTheQuestion() {
        fakeProvider(false, AiTask.CHAT);
        fake.then(r -> FakeLlm.text("بر اساس اطلاعات شما …"));
        long id = conversationId(turn(null, "وضعیتم چطور است؟"));
        LlmRequest request = fake.requests().getLast();
        assertThat(request.tools()).isEmpty();
        assertThat(request.system()).contains("snapshot");
        Part.Context snapshot = (Part.Context) request.messages().getLast().parts().getFirst();
        assertThat(snapshot.text()).contains("\"net_worth_toman\":\"37,500,000\"").contains("monthly_totals");
        // the snapshot is not stored with the conversation
        assertThat(jdbc.sql("SELECT parts FROM ai_messages WHERE conversation_id = ? AND seq = 1").param(id).query(String.class).single())
                .doesNotContain("net_worth");
    }

    @Test
    void stopsATurnThatKeepsCallingTools() {
        fakeProvider(true, AiTask.CHAT);
        for (int i = 0; i < ChatService.MAX_ROUNDS; i++) {
            fake.then(r -> FakeLlm.toolCalls(FakeLlm.call("list_accounts", "{}")));
        }
        RecordingSink sink = turn(null, "حساب‌ها؟");
        assertThat(sink.last("error").get("message").toString()).contains("بیش از حد مجاز");
        assertThat(fake.requests()).hasSize(ChatService.MAX_ROUNDS);
        // the round before the last was told to answer without more tools
        LlmMessage lastResults = fake.requests().getLast().messages().getLast();
        assertThat(lastResults.parts().getLast()).isInstanceOf(Part.Context.class);
    }

    @Test
    void refusesWhatTheUserMayNotDo() {
        assertThatThrownBy(() -> chat.prepare(user.id(), null, "سلام"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.notConfigured"));
        long providerId = fakeProvider(true, AiTask.CHAT);
        assertThatThrownBy(() -> chat.prepare(user.id(), null, " "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.invalidMessage"));

        Turn first = chat.prepare(user.id(), null, "اول");
        assertThatThrownBy(() -> chat.prepare(user.id(), null, "دوم"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.busy"));
        chat.run(first, new RecordingSink());

        usage.setDailyLimit(1);
        assertThatThrownBy(() -> chat.prepare(user.id(), null, "سوم"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.quotaExceeded"));
        usage.setDailyLimit(100);

        jdbc.sql("UPDATE user_settings SET ai_enabled = false WHERE user_id = ?").param(user.id()).update();
        assertThatThrownBy(() -> chat.prepare(user.id(), null, "چهارم"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.disabled"));
        jdbc.sql("UPDATE user_settings SET ai_enabled = true WHERE user_id = ?").param(user.id()).update();

        providers.delete(providerId);
        assertThatThrownBy(() -> chat.prepare(user.id(), first.conversationId(), "پنجم"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ai.conversationUnavailable"));
        assertThat(chat.get(user.id(), first.conversationId()).available()).isFalse();
    }

    @Test
    void conversationsArePrivate() throws Exception {
        fakeProvider(true, AiTask.CHAT);
        fake.then(r -> FakeLlm.text("سلام"));
        long id = conversationId(turn(null, "حساب‌هایم"));
        TestUser other = createUser("other");
        mvc.perform(getAs(other, "/api/v1/ai/conversations/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(other, "/api/v1/ai/conversations/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(postAs(other, "/api/v1/ai/chat", Map.of("conversationId", id, "text", "سلام"))).andExpect(status().isNotFound());
        mvc.perform(getAs(other, "/api/v1/ai/conversations")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(getAs(user, "/api/v1/ai/conversations")).andExpect(jsonPath("$[0].title").value("حساب‌هایم"));
        mvc.perform(patchAs(user, "/api/v1/ai/conversations/{id}", Map.of("title", "حساب‌ها"), id)).andExpect(jsonPath("$.title").value("حساب‌ها"));
        mvc.perform(getAs(user, "/api/v1/ai/status"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tasks.CHAT").value(true))
                .andExpect(jsonPath("$.tasks.REPORT").value(false))
                .andExpect(jsonPath("$.quota.used").value(1));
        mvc.perform(deleteAs(user, "/api/v1/ai/conversations/{id}", id)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_messages").query(Integer.class).single()).isZero();
    }
}
