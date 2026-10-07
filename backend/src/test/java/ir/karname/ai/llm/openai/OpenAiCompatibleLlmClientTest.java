package ir.karname.ai.llm.openai;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolSpec TOOL = new ToolSpec("get_budget", "Budget status of a month.",
            JsonSchema.object(null).required("month", JsonSchema.string("Jalali month")).build());
    private static final StructuredOutput OUTPUT = new StructuredOutput("result",
            JsonSchema.object(null).required("ok", JsonSchema.bool(null)).build());

    private StubServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private OpenAiCompatibleLlmClient client(boolean tools, boolean jsonSchema) {
        return new OpenAiCompatibleLlmClient(new ProviderConfig(server.url() + "/v1/", "sk-test", Map.of("X-Title", "Karname"),
                Map.of("api-version", "2026-01-01"), tools, jsonSchema, true, false));
    }

    private static LlmRequest request(List<LlmMessage> messages, StructuredOutput output) {
        return new LlmRequest("gpt-test", "SYSTEM", messages, List.of(TOOL), output, Effort.MEDIUM, 2000);
    }

    private static String events(String... chunks) {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append("data: ").append(chunk).append("\n\n");
        }
        return out.append("data: [DONE]\n\n").toString();
    }

    @Test
    void streamsTextAndAssemblesToolCallFragments() {
        server = StubServer.start(r -> Reply.sse(events(
                "{\"model\":\"gpt-test-2026\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"بررسی \"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"می‌کنم\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_budget\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"month\\\":\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"1405-07\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":60}}}")));
        List<String> deltas = new ArrayList<>();
        LlmResponse response = client(true, true).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("بودجه؟")))), null),
                new LlmListener() {
                    @Override
                    public void onText(String delta) {
                        deltas.add(delta);
                    }
                });

        assertThat(deltas).containsExactly("بررسی ", "می‌کنم");
        assertThat(response.text()).isEqualTo("بررسی می‌کنم");
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("call_1");
            assertThat(c.input()).isEqualTo("{\"month\":\"1405-07\"}");
        });
        assertThat(response.usage().inputTokens()).isEqualTo(40);
        assertThat(response.usage().cacheReadTokens()).isEqualTo(60);
        assertThat(response.model()).isEqualTo("gpt-test-2026");

        StubServer.Recorded post = server.requests().getFirst();
        assertThat(post.path()).isEqualTo("/v1/chat/completions");
        assertThat(post.query()).isEqualTo("api-version=2026-01-01");
        assertThat(post.header("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(post.header("X-Title")).isEqualTo("Karname");
        JsonNode body = JSON.readTree(post.body());
        assertThat(body.at("/messages/0/role").asString()).isEqualTo("system");
        assertThat(body.at("/tools/0/function/name").asString()).isEqualTo("get_budget");
        assertThat(body.at("/tools/0/function/parameters/additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.get("tool_choice").asString()).isEqualTo("auto");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.at("/stream_options/include_usage").asBoolean()).isTrue();
        assertThat(body.has("max_tokens")).isFalse();
    }

    @Test
    void sendsHistoryInChatCompletionsShapeAndEchoesReasoningOnlyWithinTheTurn() {
        server = StubServer.start(r -> Reply.sse(events("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"تمام\"},\"finish_reason\":\"stop\"}]}")));
        LlmMessage.Assistant oldTurn = new LlmMessage.Assistant(List.of(new Part.Text("قبلی")), new LlmMessage.Native(
                OpenAiCompatibleLlmClient.NATIVE_FORMAT, "{\"role\":\"assistant\",\"content\":\"قبلی\",\"reasoning_content\":\"old thoughts\"}"));
        LlmMessage.Assistant toolTurn = new LlmMessage.Assistant(List.of(new Part.ToolCall("call_7", "get_budget", "{\"month\":\"1405-07\"}")),
                new LlmMessage.Native(OpenAiCompatibleLlmClient.NATIVE_FORMAT,
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_7\",\"type\":\"function\",\"function\":{\"name\":\"get_budget\",\"arguments\":\"{}\"}}],\"reasoning_content\":\"current thoughts\"}"));
        List<LlmMessage> history = List.of(
                new LlmMessage.User(List.of(new Part.Text("اول"))), oldTurn,
                new LlmMessage.User(List.of(new Part.Context("امروز ۱۴ مهر"), new Part.Text("دوم"))), toolTurn,
                new LlmMessage.User(List.of(new Part.ToolResult("call_7", "{\"status\":\"OK\"}", false, "{\"ui\":1}"))));
        LlmResponse response = client(true, true).send(request(history, null), LlmListener.NONE);
        assertThat(response.stopReason()).isEqualTo(StopReason.END);

        JsonNode messages = JSON.readTree(server.requests().getFirst().body()).get("messages");
        assertThat(messages).hasSize(6);
        assertThat(messages.get(2).has("reasoning_content")).isFalse();
        assertThat(messages.get(3).get("content").asString()).isEqualTo("<context>\nامروز ۱۴ مهر\n</context>\n\nدوم");
        assertThat(messages.get(4).get("reasoning_content").asString()).isEqualTo("current thoughts");
        assertThat(messages.get(5).get("role").asString()).isEqualTo("tool");
        assertThat(messages.get(5).get("tool_call_id").asString()).isEqualTo("call_7");
        assertThat(messages.get(5).get("content").asString()).isEqualTo("{\"status\":\"OK\"}");
    }

    @Test
    void asksForTheSchemaOrInThePromptDependingOnTheServer() {
        server = StubServer.start(r -> Reply.sse(events("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}")));
        client(true, true).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("…")))), OUTPUT), LlmListener.NONE);
        JsonNode native1 = JSON.readTree(server.requests().getFirst().body());
        assertThat(native1.at("/response_format/type").asString()).isEqualTo("json_schema");
        assertThat(native1.at("/response_format/json_schema/name").asString()).isEqualTo("result");
        assertThat(native1.at("/messages/0/content").asString()).isEqualTo("SYSTEM");

        client(false, false).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("…")))), OUTPUT), LlmListener.NONE);
        JsonNode prompted = JSON.readTree(server.requests().get(1).body());
        assertThat(prompted.has("response_format")).isFalse();
        assertThat(prompted.has("tools")).isFalse();
        assertThat(prompted.at("/messages/0/content").asString()).startsWith("SYSTEM").contains("# Output format");
    }

    @Test
    void acceptsAWholeJsonResponseInsteadOfAStream() {
        server = StubServer.start(r -> Reply.json(200, """
                {"model":"llama3.1","choices":[{"index":0,"message":{"role":"assistant","content":"",
                 "tool_calls":[{"id":"call_a","type":"function","function":{"name":"get_budget","arguments":{"month":"1405-06"}}}]},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3}}
                """));
        LlmResponse response = client(true, true).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("…")))), null),
                LlmListener.NONE);
        // a tool call counts even when the server reports "stop"
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(JSON.readTree(response.toolCalls().getFirst().input()).get("month").asString()).isEqualTo("1405-06");
    }

    @Test
    void translatesErrorsWithTheProvidersMessage() {
        server = StubServer.start(r -> Reply.json(401, "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}"));
        assertThatThrownBy(() -> client(true, true).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("…")))), null),
                LlmListener.NONE))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.AUTH);
                    assertThat(e.detail()).isEqualTo("HTTP 401: Incorrect API key provided");
                });

        server.respond(r -> Reply.sse(events("{\"error\":{\"message\":\"model overloaded\"}}")));
        assertThatThrownBy(() -> client(true, true).send(request(List.of(new LlmMessage.User(List.of(new Part.Text("…")))), null),
                LlmListener.NONE))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.UNAVAILABLE));
    }

    @Test
    void listsModels() {
        server = StubServer.start(r -> r.path().equals("/v1/models")
                ? Reply.json(200, "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3:8b\"},{\"id\":\"gemma3:12b\"}]}") : null);
        assertThat(client(true, true).listModels()).containsExactly("gemma3:12b", "qwen3:8b");
    }
}
