package ir.karname.ai.llm.fake;

import ir.karname.ai.llm.Effort;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.ToolSpec;
import ir.karname.common.jalali.JalaliDate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FakeBehaviorTest {

    @Test
    void readsSimpleNotesForDemos() {
        List<Map<String, Object>> parsed = FakeBehavior.parseNote("دیروز ناهار 180 و اسنپ 95 تومن", JalaliDate.of(1405, 7, 14));
        assertThat(parsed).hasSize(2);
        assertThat(parsed.getFirst()).containsEntry("description", "ناهار").containsEntry("scale", "THOUSAND")
                .containsEntry("date", "1405/07/13").containsEntry("type", "EXPENSE");
        assertThat(parsed.get(1).get("amount")).isEqualTo(new BigDecimal("95"));
        assertThat(FakeBehavior.parseNote("حقوق 45 میلیون واریز شد", JalaliDate.of(1405, 7, 14)).getFirst())
                .containsEntry("type", "INCOME").containsEntry("scale", "MILLION");
        assertThat(FakeBehavior.parseNote("سلام", JalaliDate.of(1405, 7, 14))).isEmpty();
    }

    @Test
    void passesTheConnectionTestAndOnlyCallsOfferedTools() {
        ToolSpec ping = new ToolSpec("ping", "Echo", JsonSchema.object(null).required("word", JsonSchema.string("w")).build());
        LlmResponse pong = FakeBehavior.respond(request(List.of(ping), "Call the ping tool"));
        assertThat(pong.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("ping");
            assertThat(call.input()).isEqualTo("{\"word\":\"karname\"}");
        });

        // a question whose tool is not on offer gets a plain answer instead of a call to a missing tool
        ToolSpec calculate = new ToolSpec("calculate", "Math", JsonSchema.object(null).required("expression", JsonSchema.string("e")).build());
        LlmResponse answer = FakeBehavior.respond(request(List.of(calculate), "وضعیت بودجه چطور است؟"));
        assertThat(answer.toolCalls()).isEmpty();
        assertThat(answer.text()).contains("پاسخ آزمایشی");
    }

    private static LlmRequest request(List<ToolSpec> tools, String question) {
        return new LlmRequest(FakeLlm.MODEL, "system", List.of(new LlmMessage.User(List.of(new Part.Text(question)))), tools, null,
                Effort.LOW, 1024);
    }
}
