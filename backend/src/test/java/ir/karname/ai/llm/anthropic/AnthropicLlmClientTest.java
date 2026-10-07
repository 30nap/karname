package ir.karname.ai.llm.anthropic;

import ir.karname.ai.llm.Effort;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.ProviderConfig;
import ir.karname.ai.llm.StopReason;
import ir.karname.ai.llm.StructuredOutput;
import ir.karname.ai.llm.ToolSpec;
import ir.karname.support.StubServer;
import ir.karname.support.StubServer.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicLlmClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolSpec TOOL = new ToolSpec("get_budget", "Budget status of a month.",
            JsonSchema.object(null).required("month", JsonSchema.string("Jalali month, yyyy-mm")).build());

    private StubServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private AnthropicLlmClient client(boolean official) {
        return new AnthropicLlmClient(new ProviderConfig(server.url(), "test-key", Map.of("X-Gateway", "g1"), Map.of(), true, true, false, true),
                Clock.systemUTC(), official);
    }

    private static LlmRequest request(List<LlmMessage> messages, List<ToolSpec> tools, StructuredOutput output) {
        return new LlmRequest("claude-opus-5-5", "SYSTEM PROMPT", messages, tools, output, Effort.MEDIUM, 4000);
    }

    private static LlmMessage.User user(String text) {
        return new LlmMessage.User(List.of(new Part.Context("امروز: سه‌شنبه ۱۴ مهر ۱۴۰۵"), new Part.Text(text)));
    }

    static String sse(String... events) {
        StringBuilder out = new StringBuilder();
        for (String event : events) {
            String type = JSON.readTree(event).get("type").asString();
            out.append("event: ").append(type).append('\n').append("data: ").append(event).append("\n\n");
        }
        return out.toString();
    }

    static String start(String model) {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + model
                + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":1,"
                + "\"cache_read_input_tokens\":40,\"cache_creation_input_tokens\":7}}}";
    }

    static String blockStart(int index, String block) {
        return "{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":" + block + "}";
    }

    static String delta(int index, String delta) {
        return "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}";
    }

    static String blockStop(int index) {
        return "{\"type\":\"content_block_stop\",\"index\":" + index + "}";
    }

    static String messageDelta(String stopReason, String extra) {
        return "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null" + extra
                + "},\"usage\":{\"output_tokens\":25}}";
    }

    static final String STOP = "{\"type\":\"message_stop\"}";

    static String textReply(String text) {
        return sse(start("claude-opus-5-5"), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"),
                delta(0, "{\"type\":\"text_delta\",\"text\":\"" + text + "\"}"), blockStop(0), messageDelta("end_turn", ""), STOP);
    }

    private static JsonNode body(StubServer.Recorded recorded) {
        return JSON.readTree(recorded.body());
    }

    @Test
    void sendsCachedSystemPromptStrictToolsAdaptiveThinkingAndEffort() {
        server = StubServer.start(r -> r.method().equals("POST") ? Reply.sse(textReply("سلام")) : null);
        List<String> deltas = new ArrayList<>();
        LlmResponse response = client(false).send(request(List.of(user("وضع بودجه؟")), List.of(TOOL), null), new LlmListener() {
            @Override
            public void onText(String delta) {
                deltas.add(delta);
            }
        });

        assertThat(response.text()).isEqualTo("سلام");
        assertThat(deltas).containsExactly("سلام");
        assertThat(response.stopReason()).isEqualTo(StopReason.END);
        assertThat(response.usage().cacheReadTokens()).isEqualTo(40);
        assertThat(response.usage().cacheWriteTokens()).isEqualTo(7);

        StubServer.Recorded post = server.requests("POST", "/v1/messages").getFirst();
        assertThat(post.header("x-api-key")).isEqualTo("test-key");
        assertThat(post.header("X-Gateway")).isEqualTo("g1");
        // beta options are kept for the Anthropic API itself; a gateway might reject them
        assertThat(post.header("anthropic-beta")).isNull();
        JsonNode body = body(post);
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(4000);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.at("/system/0/text").asString()).isEqualTo("SYSTEM PROMPT");
        assertThat(body.at("/system/0/cache_control/type").asString()).isEqualTo("ephemeral");
        assertThat(body.at("/tools/0/name").asString()).isEqualTo("get_budget");
        assertThat(body.at("/tools/0/strict").asBoolean()).isTrue();
        assertThat(body.at("/tools/0/input_schema/additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.at("/tools/0/input_schema/required/0").asString()).isEqualTo("month");
        assertThat(body.at("/tools/0").has("eager_input_streaming")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.at("/thinking/type").asString()).isEqualTo("adaptive");
        assertThat(body.at("/thinking").has("block_binding")).isFalse();
        assertThat(body.at("/output_config/effort").asString()).isEqualTo("medium");
        assertThat(body.has("cache_control")).isFalse();
        assertThat(body.has("fallbacks")).isFalse();
        assertThat(body.at("/messages/0/role").asString()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/text").asString()).isEqualTo("<context>\nامروز: سه‌شنبه ۱۴ مهر ۱۴۰۵\n</context>");
        assertThat(body.at("/messages/0/content/1/text").asString()).isEqualTo("وضع بودجه؟");
    }

    @Test
    void onTheAnthropicApiAlsoUsesAutomaticCachingFallbacksAndLenientThinkingBinding() {
        server = StubServer.start(r -> r.method().equals("POST") ? Reply.sse(textReply("باشه")) : null);
        client(true).send(request(List.of(user("سلام")), List.of(TOOL), null), LlmListener.NONE);

        StubServer.Recorded post = server.requests("POST", "/v1/messages").getFirst();
        assertThat(post.header("anthropic-beta")).contains("thinking-binding-controls-2026-08-01").contains("server-side-fallback-2026-07-01");
        JsonNode body = body(post);
        assertThat(body.at("/cache_control/type").asString()).isEqualTo("ephemeral");
        assertThat(body.get("fallbacks").asString()).isEqualTo("default");
        assertThat(body.at("/thinking/block_binding/prefix_mismatch_behavior").asString()).isEqualTo("drop_block");
        assertThat(body.at("/tools/0/eager_input_streaming").asBoolean()).isTrue();
    }

    @Test
    void replaysThinkingAndToolCallsExactlyAsReceived() {
        String toolTurn = sse(start("claude-opus-5-5"),
                blockStart(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}"),
                delta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"باید بودجه را ببینم\"}"),
                delta(0, "{\"type\":\"signature_delta\",\"signature\":\"sig-1\"}"),
                blockStop(0),
                blockStart(1, "{\"type\":\"text\",\"text\":\"\"}"),
                delta(1, "{\"type\":\"text_delta\",\"text\":\"بررسی می‌کنم.\"}"),
                blockStop(1),
                blockStart(2, "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_budget\",\"input\":{}}"),
                delta(2, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"month\\\":\"}"),
                delta(2, "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"1405-07\\\"}\"}"),
                blockStop(2),
                messageDelta("tool_use", ""), STOP);
        AtomicInteger posts = new AtomicInteger();
        server = StubServer.start(r -> r.method().equals("POST")
                ? Reply.sse(posts.getAndIncrement() == 0 ? toolTurn : textReply("بودجه سالم است.")) : null);
        AnthropicLlmClient client = client(false);

        LlmResponse first = client.send(request(List.of(user("بودجه؟")), List.of(TOOL), null), LlmListener.NONE);
        assertThat(first.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(first.text()).isEqualTo("بررسی می‌کنم.");
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_1");
            assertThat(call.name()).isEqualTo("get_budget");
            assertThat(JSON.readTree(call.input()).get("month").asString()).isEqualTo("1405-07");
        });

        List<LlmMessage> history = List.of(user("بودجه؟"), first.toMessage(),
                new LlmMessage.User(List.of(new Part.ToolResult("toolu_1", "{\"status\":\"OK\"}", false, null))));
        LlmResponse second = client.send(request(history, List.of(TOOL), null), LlmListener.NONE);
        assertThat(second.text()).isEqualTo("بودجه سالم است.");

        JsonNode replayed = body(server.requests("POST", "/v1/messages").get(1)).at("/messages/1");
        assertThat(replayed.get("role").asString()).isEqualTo("assistant");
        assertThat(replayed.at("/content/0")).isEqualTo(JSON.readTree("{\"type\":\"thinking\",\"signature\":\"sig-1\",\"thinking\":\"باید بودجه را ببینم\"}"));
        assertThat(replayed.at("/content/1/text").asString()).isEqualTo("بررسی می‌کنم.");
        assertThat(replayed.at("/content/2")).isEqualTo(JSON.readTree(
                "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"input\":{\"month\":\"1405-07\"},\"name\":\"get_budget\"}"));
        JsonNode results = body(server.requests("POST", "/v1/messages").get(1)).at("/messages/2/content/0");
        assertThat(results.get("type").asString()).isEqualTo("tool_result");
        assertThat(results.get("tool_use_id").asString()).isEqualTo("toolu_1");
        assertThat(results.get("content").asString()).isEqualTo("{\"status\":\"OK\"}");
        assertThat(results.has("is_error")).isFalse();
    }

    @Test
    void reportsARefusalWithItsCategory() {
        server = StubServer.start(r -> r.method().equals("POST") ? Reply.sse(sse(start("claude-opus-5-5"),
                messageDelta("refusal", ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":null}"), STOP)) : null);
        LlmResponse response = client(false).send(request(List.of(user("…")), List.of(), null), LlmListener.NONE);
        assertThat(response.stopReason()).isEqualTo(StopReason.REFUSAL);
        assertThat(response.refusalCategory()).isEqualTo("cyber");
        assertThat(response.parts()).isEmpty();
    }

    @Test
    void afterAFallbackSwitchOnlyTheDeclinedModelsTextCarriesOver() {
        server = StubServer.start(r -> r.method().equals("POST") ? Reply.sse(sse(start("claude-opus-5"),
                blockStart(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"declined-sig\"}"), blockStop(0),
                blockStart(1, "{\"type\":\"text\",\"text\":\"نیمه‌ی اول\"}"), blockStop(1),
                blockStart(2, "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-5\"}}"), blockStop(2),
                blockStart(3, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"kept-sig\"}"), blockStop(3),
                blockStart(4, "{\"type\":\"text\",\"text\":\" و بقیه\"}"), blockStop(4),
                blockStart(5, "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"get_budget\",\"input\":{}}"),
                delta(5, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"month\\\":\\\"1405-07\\\"}\"}"), blockStop(5),
                messageDelta("tool_use", ""), STOP)) : null);
        LlmResponse response = client(false).send(request(List.of(user("…")), List.of(TOOL), null), LlmListener.NONE);

        assertThat(response.text()).isEqualTo("نیمه‌ی اول و بقیه");
        assertThat(response.toolCalls()).extracting(Part.ToolCall::id).containsExactly("toolu_9");
        assertThat(response.model()).isEqualTo("claude-opus-5");
        JsonNode replay = JSON.readTree(response.nativeMessage().json());
        assertThat(replay).hasSize(4);
        assertThat(replay.get(0).get("type").asString()).isEqualTo("text");
        assertThat(replay.get(1).get("signature").asString()).isEqualTo("kept-sig");
        assertThat(replay.get(3).get("type").asString()).isEqualTo("tool_use");
    }

    @Test
    void retriesWithoutAnOptionTheModelRejects() {
        AtomicInteger posts = new AtomicInteger();
        server = StubServer.start(r -> {
            if (!r.method().equals("POST")) {
                return null;
            }
            return posts.getAndIncrement() == 0
                    ? Reply.json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"output_config.effort: Extra inputs are not permitted\"}}")
                    : Reply.sse(textReply("ok"));
        });
        AnthropicLlmClient client = client(false);
        assertThat(client.send(request(List.of(user("…")), List.of(), null), LlmListener.NONE).text()).isEqualTo("ok");
        List<StubServer.Recorded> sent = server.requests("POST", "/v1/messages");
        assertThat(sent).hasSize(2);
        assertThat(body(sent.get(1)).has("output_config")).isFalse();

        // remembered: later requests leave it out without a failed attempt
        client.send(request(List.of(user("…")), List.of(), null), LlmListener.NONE);
        assertThat(server.requests("POST", "/v1/messages")).hasSize(3);
    }

    @Test
    void stripsEarlierReasoningWhenTheProviderNoLongerAcceptsIt() {
        AtomicInteger posts = new AtomicInteger();
        server = StubServer.start(r -> {
            if (!r.method().equals("POST")) {
                return null;
            }
            return posts.getAndIncrement() == 0
                    ? Reply.json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages.1.content.0: Invalid `signature` in `thinking` block.\"}}")
                    : Reply.sse(textReply("ok"));
        });
        LlmMessage.Assistant earlier = new LlmMessage.Assistant(List.of(new Part.Text("قبلی")), new LlmMessage.Native(
                AnthropicLlmClient.NATIVE_FORMAT, "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"old\"},{\"type\":\"text\",\"text\":\"قبلی\"}]"));
        client(false).send(request(List.of(user("اول"), earlier, user("دوم")), List.of(), null), LlmListener.NONE);

        JsonNode retried = body(server.requests("POST", "/v1/messages").get(1)).at("/messages/1/content");
        assertThat(retried).hasSize(1);
        assertThat(retried.get(0).get("type").asString()).isEqualTo("text");
    }

    @Test
    void readsCapabilitiesFromTheModelsApi() {
        server = StubServer.start(r -> {
            if (r.method().equals("GET") && r.path().equals("/v1/models/claude-haiku-4-5")) {
                return Reply.json(200, """
                        {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5","created_at":"2025-10-01T00:00:00Z",
                         "capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":false},"enabled":{"supported":true}}},
                         "effort":{"supported":false,"low":{"supported":false},"medium":{"supported":false},"high":{"supported":false},"max":{"supported":false}},
                         "structured_outputs":{"supported":false}}}
                        """);
            }
            return r.method().equals("POST") ? Reply.sse(textReply("{\\\"ok\\\":true}")) : null;
        });
        StructuredOutput output = new StructuredOutput("result", JsonSchema.object(null).required("ok", JsonSchema.bool(null)).build());
        client(false).send(new LlmRequest("claude-haiku-4-5", "SYS", List.of(user("…")), List.of(), output, Effort.LOW, 1000), LlmListener.NONE);

        JsonNode body = body(server.requests("POST", "/v1/messages").getFirst());
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("output_config")).isFalse();
        // no schema support: the prompt asks for the JSON instead
        assertThat(body.at("/system/0/text").asString()).startsWith("SYS").contains("# Output format").contains("\"ok\"");
    }

    @Test
    void sendsTheSchemaWhenTheModelSupportsStructuredOutputs() {
        server = StubServer.start(r -> r.method().equals("POST") ? Reply.sse(textReply("{}")) : null);
        StructuredOutput output = new StructuredOutput("result", JsonSchema.object(null).required("ok", JsonSchema.bool(null)).build());
        client(false).send(request(List.of(user("…")), List.of(), output), LlmListener.NONE);

        JsonNode body = body(server.requests("POST", "/v1/messages").getFirst());
        assertThat(body.at("/output_config/format/type").asString()).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema/additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.at("/system/0/text").asString()).isEqualTo("SYSTEM PROMPT");
    }

    @Test
    void translatesAuthenticationFailures() {
        server = StubServer.start(r -> Reply.json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        assertThatThrownBy(() -> client(false).send(request(List.of(user("…")), List.of(), null), LlmListener.NONE))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.AUTH);
                    assertThat(e.userMessage()).contains("کلید API");
                });
    }

    @Test
    void guessesCapabilitiesFromTheModelName() {
        assertThat(AnthropicLlmClient.guess("claude-opus-5-5")).isEqualTo(new AnthropicLlmClient.Capabilities(true, true, true));
        assertThat(AnthropicLlmClient.guess("claude-sonnet-4-6")).isEqualTo(new AnthropicLlmClient.Capabilities(true, true, true));
        assertThat(AnthropicLlmClient.guess("claude-opus-4-5")).isEqualTo(new AnthropicLlmClient.Capabilities(false, true, true));
        assertThat(AnthropicLlmClient.guess("claude-haiku-4-5")).isEqualTo(new AnthropicLlmClient.Capabilities(false, false, true));
        assertThat(AnthropicLlmClient.guess("claude-sonnet-4-0")).isEqualTo(new AnthropicLlmClient.Capabilities(false, false, false));
        assertThat(AnthropicLlmClient.baseUrl("https://api.anthropic.com/v1/")).isEqualTo("https://api.anthropic.com");
    }
}
