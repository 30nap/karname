package ir.karname.ai.llm.fake;

import ir.karname.ai.llm.JsonSchema;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.LlmUsage;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.StopReason;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The behavior behind {@link FakeLlmClient}: steps queued by a test answer in order; without them
 * the model answers deterministically (calls the overview tool once, then replies; returns a
 * minimal valid object when JSON is asked for). Every request is recorded for assertions.
 */
@Component
@ConditionalOnProperty(name = "karname.ai.fake-enabled", havingValue = "true")
public class FakeLlm {

    public static final String MODEL = "karname-fake";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LlmUsage USAGE = new LlmUsage(120, 30, 0, 0);
    private static final AtomicInteger IDS = new AtomicInteger();

    private final Deque<Function<LlmRequest, LlmResponse>> script = new ConcurrentLinkedDeque<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    public LlmResponse respond(LlmRequest request) {
        requests.add(request);
        Function<LlmRequest, LlmResponse> step = script.poll();
        return step != null ? step.apply(request) : defaultResponse(request);
    }

    public FakeLlm then(Function<LlmRequest, LlmResponse> step) {
        script.add(step);
        return this;
    }

    public FakeLlm then(LlmResponse response) {
        return then(r -> response);
    }

    /** The next request fails as a provider failure of this kind would. */
    public FakeLlm thenFail(LlmException.Kind kind) {
        return then(r -> {
            throw new LlmException(kind, "fake failure");
        });
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        script.clear();
        requests.clear();
    }

    public static LlmResponse text(String text) {
        return new LlmResponse(List.of(new Part.Text(text)), null, StopReason.END, null, USAGE, MODEL);
    }

    public static LlmResponse toolCalls(Part.ToolCall... calls) {
        return new LlmResponse(List.of(calls), null, StopReason.TOOL_USE, null, USAGE, MODEL);
    }

    public static LlmResponse refusal() {
        return new LlmResponse(List.of(), null, StopReason.REFUSAL, "cyber", USAGE, MODEL);
    }

    public static Part.ToolCall call(String name, String input) {
        return new Part.ToolCall("call_fake_" + IDS.incrementAndGet(), name, input);
    }

    private static LlmResponse defaultResponse(LlmRequest request) {
        if (request.output() != null) {
            return text(JSON.writeValueAsString(example(request.output().schema())));
        }
        List<LlmMessage> messages = request.messages();
        LlmMessage last = messages.isEmpty() ? null : messages.getLast();
        boolean newTurn = last instanceof LlmMessage.User u && u.parts().stream().anyMatch(p -> p instanceof Part.Text);
        if (newTurn && request.tools().stream().anyMatch(t -> t.name().equals("get_financial_overview"))) {
            return toolCalls(call("get_financial_overview", "{}"));
        }
        return text("این پاسخ آزمایشی دستیار است؛ اطلاعات مالی شما بررسی شد.");
    }

    /** The smallest object a schema accepts. */
    static Object example(JsonSchema schema) {
        return switch (schema.type()) {
            case OBJECT -> {
                Map<String, Object> object = new LinkedHashMap<>();
                schema.required().forEach(name -> object.put(name, example(schema.properties().get(name))));
                yield object;
            }
            case ARRAY -> new ArrayList<>();
            case STRING -> schema.values().isEmpty() ? "" : schema.values().getFirst();
            case INTEGER, NUMBER -> 0;
            case BOOLEAN -> false;
        };
    }
}
