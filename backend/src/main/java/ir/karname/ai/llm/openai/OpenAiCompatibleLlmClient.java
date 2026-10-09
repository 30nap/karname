package ir.karname.ai.llm.openai;

import ir.karname.ai.llm.LlmClient;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.LlmUsage;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.ProviderConfig;
import ir.karname.ai.llm.StopReason;
import ir.karname.ai.llm.StructuredReply;
import ir.karname.ai.llm.ToolSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Any server speaking OpenAI's Chat Completions API: OpenAI, Gemini's compatible endpoint,
 * DeepSeek, Mistral, xAI, Groq, OpenRouter, gateways, and local servers (Ollama, LM Studio,
 * vLLM). Replies are streamed (Server-Sent Events) and tool-call fragments assembled; servers that
 * answer with one JSON body instead are handled too. Only widely accepted fields are sent, so
 * options some servers reject (output limits, strict schemas) are left out.
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    public static final String NATIVE_FORMAT = "openai";

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Until the response starts; local models can take long to load. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_ERROR_CHARS = 600;

    private final ProviderConfig config;
    private final String baseUrl;
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(ProviderConfig config) {
        this.config = config;
        String url = config.baseUrl() == null ? "" : config.baseUrl().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // a redirect could carry the key headers to another host, or reach an address the settings would refuse
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public boolean supportsStructuredOutput(String model) {
        return config.jsonSchema();
    }

    @Override
    public boolean supportsTools(String model) {
        return config.tools();
    }

    @Override
    public LlmResponse send(LlmRequest request, LlmListener listener) {
        String body = JSON.writeValueAsString(body(request));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/chat/completions"))
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        authorize(builder);
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                listener.streaming(in);
                if (response.statusCode() / 100 != 2) {
                    throw httpError(response.statusCode(), new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8));
                }
                Accumulator acc = new Accumulator();
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (type.startsWith("application/json")) {
                    acc.chunk(JSON.readTree(in), listener);
                } else {
                    readEvents(in, acc, listener);
                }
                return acc.response(request.model());
            }
        } catch (LlmException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, e.getMessage(), e);
        } catch (IOException | JacksonException e) {
            if (listener.cancelled()) {
                throw new LlmException(LlmException.Kind.CANCELLED, null, e);
            }
            throw e instanceof JacksonException
                    ? new LlmException(LlmException.Kind.INVALID_RESPONSE, e.getMessage(), e)
                    : new LlmException(LlmException.Kind.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.CANCELLED, null, e);
        }
    }

    ObjectNode body(LlmRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        boolean prompted = request.output() != null && !config.jsonSchema();
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", prompted ? request.system() + StructuredReply.instructions(request.output()) : request.system());
        appendMessages(messages, request.messages());
        if (!request.tools().isEmpty() && config.tools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", spec.name()).put("description", spec.description());
                function.set("parameters", JSON.valueToTree(spec.input().toMap()));
            }
            body.put("tool_choice", "auto");
        }
        if (request.output() != null && config.jsonSchema()) {
            ObjectNode schema = body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
            schema.put("name", request.output().name()).put("strict", false);
            schema.set("schema", JSON.valueToTree(request.output().schema().toMap()));
        }
        body.put("stream", true);
        if (config.streamUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        return body;
    }

    private static void appendMessages(ArrayNode out, List<LlmMessage> history) {
        // reasoning is echoed only within the turn that is still running (DeepSeek's rule)
        int turnStart = 0;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) instanceof LlmMessage.User u && u.parts().stream().anyMatch(p -> p instanceof Part.Text)) {
                turnStart = i;
            }
        }
        for (int i = 0; i < history.size(); i++) {
            switch (history.get(i)) {
                case LlmMessage.User user -> {
                    for (Part part : user.parts()) {
                        if (part instanceof Part.ToolResult r) {
                            out.addObject().put("role", "tool").put("tool_call_id", r.callId()).put("content", r.content());
                        }
                    }
                    String text = user.parts().stream()
                            .map(p -> switch (p) {
                                case Part.Context c -> "<context>\n" + c.text() + "\n</context>";
                                case Part.Text t -> t.text();
                                default -> null;
                            })
                            .filter(s -> s != null)
                            .collect(Collectors.joining("\n\n"));
                    if (!text.isEmpty()) {
                        out.addObject().put("role", "user").put("content", text);
                    }
                }
                case LlmMessage.Assistant assistant -> out.add(assistant(assistant, i > turnStart));
            }
        }
    }

    private static ObjectNode assistant(LlmMessage.Assistant message, boolean currentTurn) {
        LlmMessage.Native n = message.nativeMessage();
        if (n != null && NATIVE_FORMAT.equals(n.format())) {
            ObjectNode stored = (ObjectNode) JSON.readTree(n.json());
            if (!currentTurn) {
                stored.remove("reasoning_content");
            }
            return stored;
        }
        ObjectNode node = JSON.createObjectNode().put("role", "assistant");
        node.put("content", message.parts().stream().filter(p -> p instanceof Part.Text).map(p -> ((Part.Text) p).text())
                .collect(Collectors.joining()));
        List<Part.ToolCall> calls = message.parts().stream().filter(p -> p instanceof Part.ToolCall).map(p -> (Part.ToolCall) p).toList();
        if (!calls.isEmpty()) {
            ArrayNode array = node.putArray("tool_calls");
            calls.forEach(c -> array.addObject().put("id", c.id()).put("type", "function")
                    .putObject("function").put("name", c.name()).put("arguments", c.input()));
        }
        return node;
    }

    private static void readEvents(InputStream in, Accumulator acc, LlmListener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (listener.cancelled()) {
                throw new LlmException(LlmException.Kind.CANCELLED, null);
            }
            if (line.isEmpty()) {
                if (dispatch(data, acc, listener)) {
                    return;
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
            }
            // "event:", "id:" and ":" comment lines carry nothing needed here
        }
        dispatch(data, acc, listener);
    }

    /** Handles one event; true at the end-of-stream marker. */
    private static boolean dispatch(StringBuilder data, Accumulator acc, LlmListener listener) {
        String payload = data.toString().trim();
        data.setLength(0);
        if (payload.isEmpty()) {
            return false;
        }
        if (payload.equals("[DONE]")) {
            return true;
        }
        acc.chunk(JSON.readTree(payload), listener);
        return false;
    }

    /** Collects a streamed (or whole) completion. */
    private static final class Accumulator {

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
        private String finish;
        private LlmUsage usage = LlmUsage.ZERO;
        private String model;

        void chunk(JsonNode chunk, LlmListener listener) {
            if (chunk.has("error") && !chunk.get("error").isNull()) {
                JsonNode error = chunk.get("error");
                String message = error.isObject() ? error.path("message").asString(error.toString()) : error.asString();
                throw new LlmException(LlmException.Kind.UNAVAILABLE, truncate(message));
            }
            if (chunk.hasNonNull("model")) {
                model = chunk.get("model").asString();
            }
            JsonNode choices = chunk.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.has("delta") ? choice.get("delta") : choice.path("message");
                String content = string(delta.path("content"));
                if (!content.isEmpty()) {
                    text.append(content);
                    listener.onText(content);
                }
                reasoning.append(string(delta.path("reasoning_content")));
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        ToolCallBuilder builder = calls.computeIfAbsent(index(call), k -> new ToolCallBuilder());
                        if (call.hasNonNull("id") && !call.get("id").asString().isEmpty()) {
                            builder.id = call.get("id").asString();
                        }
                        JsonNode function = call.path("function");
                        if (function.hasNonNull("name") && !function.get("name").asString().isEmpty()) {
                            builder.name = function.get("name").asString();
                        }
                        JsonNode arguments = function.path("arguments");
                        builder.arguments.append(arguments.isObject() ? arguments.toString() : string(arguments));
                    }
                }
                if (choice.hasNonNull("finish_reason")) {
                    finish = choice.get("finish_reason").asString();
                }
            }
            JsonNode u = chunk.path("usage");
            if (u.isObject()) {
                long cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(0);
                usage = new LlmUsage(Math.max(0, u.path("prompt_tokens").asLong(0) - cached), u.path("completion_tokens").asLong(0), cached, 0);
            }
        }

        /**
         * Which call a fragment belongs to. Servers that omit {@code index} (some Gemini versions)
         * send each call whole, or continue the last one in fragments without an id.
         */
        private int index(JsonNode call) {
            if (call.hasNonNull("index")) {
                return call.get("index").asInt();
            }
            if (call.hasNonNull("id")) {
                String id = call.get("id").asString();
                for (Map.Entry<Integer, ToolCallBuilder> e : calls.entrySet()) {
                    if (id.equals(e.getValue().id)) {
                        return e.getKey();
                    }
                }
                return calls.size();
            }
            return Math.max(calls.size() - 1, 0);
        }

        LlmResponse response(String requestedModel) {
            List<Part> parts = new ArrayList<>();
            if (!text.isEmpty()) {
                parts.add(new Part.Text(text.toString()));
            }
            ObjectNode stored = JSON.createObjectNode().put("role", "assistant").put("content", text.toString());
            if (!calls.isEmpty()) {
                ArrayNode array = stored.putArray("tool_calls");
                for (ToolCallBuilder call : calls.values()) {
                    String id = call.id != null ? call.id : "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                    String arguments = call.arguments.toString().isBlank() ? "{}" : call.arguments.toString();
                    parts.add(new Part.ToolCall(id, call.name, arguments));
                    array.addObject().put("id", id).put("type", "function")
                            .putObject("function").put("name", call.name).put("arguments", arguments);
                }
            }
            if (!reasoning.isEmpty()) {
                stored.put("reasoning_content", reasoning.toString());
            }
            StopReason stop = !calls.isEmpty() ? StopReason.TOOL_USE : switch (finish == null ? "stop" : finish) {
                case "stop", "end_turn", "eos" -> StopReason.END;
                case "length", "max_tokens" -> StopReason.MAX_TOKENS;
                case "content_filter" -> StopReason.REFUSAL;
                default -> StopReason.OTHER;
            };
            if (stop == StopReason.TOOL_USE && calls.values().stream().anyMatch(c -> c.name == null || c.name.isBlank())) {
                throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "tool call without a name");
            }
            return new LlmResponse(parts, new LlmMessage.Native(NATIVE_FORMAT, JSON.writeValueAsString(stored)), stop,
                    stop == StopReason.REFUSAL ? "content_filter" : null, usage, model != null ? model : requestedModel);
        }
    }

    private static final class ToolCallBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static String string(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asString("");
    }

    @Override
    public List<String> listModels() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/models")).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").GET();
        authorize(builder);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw httpError(response.statusCode(), response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode data = root.has("data") ? root.get("data") : root.path("models");
            List<String> ids = new ArrayList<>();
            for (JsonNode model : data) {
                String id = model.isString() ? model.asString() : string(model.has("id") ? model.get("id") : model.path("name"));
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            ids.sort(String::compareTo);
            return ids;
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, e.getMessage(), e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (JacksonException e) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.CANCELLED, null, e);
        }
    }

    private void authorize(HttpRequest.Builder builder) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.setHeader("Authorization", "Bearer " + config.apiKey());
        }
        builder.setHeader("User-Agent", "Karname/1.0");
        // custom headers win, e.g. Azure's "api-key" or a gateway's own authentication
        config.headers().forEach(builder::setHeader);
    }

    private URI uri(String path) {
        StringBuilder url = new StringBuilder(baseUrl).append(path);
        if (!config.queryParams().isEmpty()) {
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
            url.append(config.queryParams().entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&")));
        }
        try {
            return URI.create(url.toString());
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, "invalid base URL", e);
        }
    }

    private static LlmException httpError(int status, String body) {
        String message = body == null ? "" : body;
        try {
            JsonNode root = JSON.readTree(message);
            JsonNode error = root.path("error");
            if (error.isObject() && error.hasNonNull("message")) {
                message = error.get("message").asString();
            } else if (error.isString()) {
                message = error.asString();
            } else if (root.hasNonNull("message")) {
                message = root.get("message").asString();
            }
        } catch (JacksonException ignored) {
            // not JSON: keep the text
        }
        String detail = "HTTP " + status + ": " + truncate(message);
        LlmException.Kind kind = switch (status) {
            case 401, 403 -> LlmException.Kind.AUTH;
            case 429 -> LlmException.Kind.RATE_LIMIT;
            case 400, 404, 405, 409, 413, 415, 422 -> LlmException.Kind.BAD_REQUEST;
            default -> status >= 500 ? LlmException.Kind.UNAVAILABLE : LlmException.Kind.BAD_REQUEST;
        };
        return new LlmException(kind, detail);
    }

    private static String truncate(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() > MAX_ERROR_CHARS ? t.substring(0, MAX_ERROR_CHARS) + "…" : t;
    }
}
