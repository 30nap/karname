package ir.karname.ai.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.SseException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRefusalStopDetails;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingBlockBinding;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaThinkingPrefixMismatchBehavior;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.BetaToolUseBlockParam;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.models.ModelCapabilities;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelRetrieveParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import ir.karname.ai.llm.Effort;
import ir.karname.ai.llm.JsonSchema;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Claude through the official Anthropic SDK (beta namespace, which types the newer request
 * options). Requests are always streamed, so long reasoning never hits an HTTP timeout.
 *
 * <ul>
 *   <li>Adaptive thinking where the model supports it, with an explicit effort per task; models'
 *       capabilities come from the Models API, falling back to their naming.</li>
 *   <li>Tools are strict (arguments conform to the schema) and chosen with {@code auto}: newer
 *       models reject forced tool choice.</li>
 *   <li>The system prompt carries a cache breakpoint (caching tools and system together); on the
 *       Anthropic API itself, automatic caching also covers the growing conversation.</li>
 *   <li>Replies are kept block for block, thinking signatures included, and replayed unchanged.
 *       Should a provider still reject earlier reasoning, it is asked to drop it rather than fail
 *       (or, behind a gateway, the reasoning is stripped and the request retried once).</li>
 *   <li>A refusal is reported before any content is read; on models with safety classifiers the
 *       API may hand a declined request to a fallback model, whose switch points are honoured
 *       when the reply is replayed.</li>
 * </ul>
 *
 * Options that a model or gateway turns out not to accept are dropped from then on, so one
 * misjudged capability costs a single retried request instead of an outage.
 */
public final class AnthropicLlmClient implements LlmClient, AutoCloseable {

    public static final String NATIVE_FORMAT = "anthropic";

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String BETA_THINKING_BINDING = "thinking-binding-controls-2026-08-01";
    private static final String BETA_FALLBACK = "server-side-fallback-2026-07-01";
    private static final Duration CAPABILITY_TTL = Duration.ofHours(6);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<List<BetaContentBlockParam>> BLOCKS = new TypeReference<>() {
    };
    /** Models with adaptive thinking: Opus/Sonnet 4.6 onwards and every later family. */
    private static final Pattern ADAPTIVE = Pattern.compile(".*claude-(opus|sonnet|fable|mythos)-(4-[6-9]|[5-9]).*");
    /** Models whose safety classifiers may decline, where a server-side fallback helps. */
    private static final Pattern CLASSIFIED = Pattern.compile(".*claude-(opus|sonnet|fable|mythos)-[5-9].*");

    /** Optional request features, named after what an API error mentions when it rejects them. */
    static final String FEATURE_FALLBACKS = "fallbacks";
    static final String FEATURE_BLOCK_BINDING = "block_binding";
    static final String FEATURE_AUTO_CACHE = "cache_control";
    static final String FEATURE_EAGER_INPUT = "eager_input_streaming";
    static final String FEATURE_EFFORT = "effort";
    static final String FEATURE_STRICT = "strict";

    private final AnthropicClient client;
    private final boolean official;
    private final boolean refusalFallback;
    private final Clock clock;
    private final Map<String, CachedCapabilities> capabilities = new ConcurrentHashMap<>();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();

    record Capabilities(boolean adaptiveThinking, boolean effort, boolean structuredOutputs) {
    }

    private record CachedCapabilities(Capabilities value, Instant expiresAt) {
    }

    public AnthropicLlmClient(ProviderConfig config) {
        this(config, Clock.systemUTC(), isOfficial(baseUrl(config.baseUrl())));
    }

    /** {@code official}: whether the endpoint is the Anthropic API itself, where beta options are known to work. */
    AnthropicLlmClient(ProviderConfig config, Clock clock, boolean official) {
        String baseUrl = baseUrl(config.baseUrl());
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl)
                // gateways may authenticate by header instead; the SDK still wants a key
                .apiKey(config.apiKey() == null || config.apiKey().isBlank() ? "none" : config.apiKey())
                .timeout(Duration.ofMinutes(5))
                .maxRetries(2);
        config.headers().forEach(builder::putHeader);
        config.queryParams().forEach(builder::putQueryParam);
        this.client = builder.build();
        this.official = official;
        this.refusalFallback = config.refusalFallback();
        this.clock = clock;
    }

    /** The SDK adds {@code /v1/...} itself; a base URL entered with it is accepted too. */
    static String baseUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.endsWith("/v1") ? u.substring(0, u.length() - 3) : u;
    }

    private static boolean isOfficial(String baseUrl) {
        try {
            return "api.anthropic.com".equalsIgnoreCase(URI.create(baseUrl).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public LlmResponse send(LlmRequest request, LlmListener listener) {
        boolean stripThinking = false;
        for (int attempt = 0; ; attempt++) {
            MessageCreateParams params = params(request, capabilities(request.model()), stripThinking);
            try {
                return stream(params, listener);
            } catch (BadRequestException e) {
                String problem = problem(e);
                if (attempt < 3) {
                    if (!stripThinking && problem.contains("thinking") && problem.contains("signature")) {
                        // earlier reasoning no longer verifies (e.g. the prompt changed between versions)
                        log.info("Provider rejected earlier thinking blocks; retrying without them");
                        stripThinking = true;
                        continue;
                    }
                    String feature = rejectedFeature(problem);
                    if (feature != null && rejected.add(feature)) {
                        log.info("Provider rejected '{}' for model {}; retrying without it", feature, request.model());
                        continue;
                    }
                }
                throw translate(e);
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                throw translate(e);
            }
        }
    }

    private LlmResponse stream(MessageCreateParams params, LlmListener listener) {
        BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> response = client.beta().messages().createStreaming(params)) {
            listener.streaming(response);
            Iterator<BetaRawMessageStreamEvent> events = response.stream().iterator();
            while (events.hasNext()) {
                if (listener.cancelled()) {
                    throw new LlmException(LlmException.Kind.CANCELLED, null);
                }
                BetaRawMessageStreamEvent event = events.next();
                accumulator.accumulate(event);
                event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> listener.onText(t.text()));
            }
        } catch (RuntimeException e) {
            if (listener.cancelled()) {
                throw new LlmException(LlmException.Kind.CANCELLED, null, e);
            }
            throw e;
        }
        return response(accumulator.message());
    }

    MessageCreateParams params(LlmRequest request, Capabilities caps, boolean stripThinking) {
        MessageCreateParams.Builder p = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                // tools render before the system prompt: this breakpoint caches both
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(request.output() != null && !caps.structuredOutputs()
                                ? request.system() + StructuredReply.instructions(request.output())
                                : request.system())
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
                .messages(messages(request.messages(), stripThinking));
        for (ToolSpec tool : request.tools()) {
            p.addTool(tool(tool));
        }
        if (caps.adaptiveThinking()) {
            BetaThinkingConfigAdaptive.Builder thinking = BetaThinkingConfigAdaptive.builder();
            if (official && !rejected.contains(FEATURE_BLOCK_BINDING)) {
                // degrade instead of failing should earlier reasoning no longer match the conversation
                thinking.blockBinding(BetaThinkingBlockBinding.builder()
                        .prefixMismatchBehavior(BetaThinkingPrefixMismatchBehavior.DROP_BLOCK)
                        .build());
                p.addBeta(BETA_THINKING_BINDING);
            }
            p.thinking(thinking.build());
        }
        BetaOutputConfig.Builder output = BetaOutputConfig.builder();
        boolean hasOutput = false;
        if (caps.effort() && request.effort() != null && !rejected.contains(FEATURE_EFFORT)) {
            output.effort(effort(request.effort()));
            hasOutput = true;
        }
        if (request.output() != null && caps.structuredOutputs()) {
            output.format(BetaJsonOutputFormat.builder()
                    .schema(BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(jsonValues(request.output().schema().toMap())).build())
                    .build());
            hasOutput = true;
        }
        if (hasOutput) {
            p.outputConfig(output.build());
        }
        if (official && !rejected.contains(FEATURE_AUTO_CACHE)) {
            // moves a cache breakpoint along with the conversation
            p.cacheControl(BetaCacheControlEphemeral.builder().build());
        }
        if (official && refusalFallback && CLASSIFIED.matcher(request.model()).matches() && !rejected.contains(FEATURE_FALLBACKS)) {
            p.fallbacksDefault();
            p.addBeta(BETA_FALLBACK);
        }
        return p.build();
    }

    private BetaToolUnion tool(ToolSpec spec) {
        JsonSchema schema = spec.input();
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        schema.properties().forEach((name, property) -> properties.put(name, JsonValue.from(property.toMap())));
        BetaTool.Builder tool = BetaTool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(BetaTool.InputSchema.Properties.builder().putAllAdditionalProperties(properties).build())
                        .required(List.copyOf(schema.required()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build());
        if (!rejected.contains(FEATURE_STRICT)) {
            tool.strict(true);
        }
        // only the Anthropic API itself is known to accept this; inputs are validated either way
        if (official && !rejected.contains(FEATURE_EAGER_INPUT)) {
            tool.eagerInputStreaming(true);
        }
        return BetaToolUnion.ofBetaTool(tool.build());
    }

    private static BetaOutputConfig.Effort effort(Effort effort) {
        return switch (effort) {
            case LOW -> BetaOutputConfig.Effort.LOW;
            case MEDIUM -> BetaOutputConfig.Effort.MEDIUM;
            case HIGH -> BetaOutputConfig.Effort.HIGH;
            case XHIGH -> BetaOutputConfig.Effort.XHIGH;
            case MAX -> BetaOutputConfig.Effort.MAX;
        };
    }

    private static Map<String, JsonValue> jsonValues(Map<String, Object> map) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        map.forEach((k, v) -> values.put(k, JsonValue.from(v)));
        return values;
    }

    private static List<BetaMessageParam> messages(List<LlmMessage> history, boolean stripThinking) {
        List<BetaMessageParam> result = new ArrayList<>(history.size());
        for (LlmMessage message : history) {
            switch (message) {
                case LlmMessage.User user -> result.add(BetaMessageParam.builder()
                        .role(BetaMessageParam.Role.USER)
                        .contentOfBetaContentBlockParams(userBlocks(user.parts()))
                        .build());
                case LlmMessage.Assistant assistant -> result.add(BetaMessageParam.builder()
                        .role(BetaMessageParam.Role.ASSISTANT)
                        .contentOfBetaContentBlockParams(assistantBlocks(assistant, stripThinking))
                        .build());
            }
        }
        return result;
    }

    /** Tool results must open a user message; text follows them. */
    private static List<BetaContentBlockParam> userBlocks(List<Part> parts) {
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Part.ToolResult r) {
                BetaToolResultBlockParam.Builder result = BetaToolResultBlockParam.builder().toolUseId(r.callId()).content(r.content());
                if (r.error()) {
                    result.isError(true);
                }
                blocks.add(BetaContentBlockParam.ofToolResult(result.build()));
            }
        }
        for (Part part : parts) {
            switch (part) {
                case Part.Context c -> blocks.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder()
                        .text("<context>\n" + c.text() + "\n</context>").build()));
                case Part.Text t -> blocks.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(t.text()).build()));
                default -> {
                }
            }
        }
        return blocks;
    }

    private static List<BetaContentBlockParam> assistantBlocks(LlmMessage.Assistant message, boolean stripThinking) {
        LlmMessage.Native nativeMessage = message.nativeMessage();
        if (nativeMessage != null && NATIVE_FORMAT.equals(nativeMessage.format())) {
            List<BetaContentBlockParam> blocks = readBlocks(nativeMessage.json());
            return stripThinking ? blocks.stream().filter(b -> !b.isThinking() && !b.isRedactedThinking()).toList() : blocks;
        }
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        for (Part part : message.parts()) {
            if (part instanceof Part.Text t && !t.text().isEmpty()) {
                blocks.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(t.text()).build()));
            } else if (part instanceof Part.ToolCall call) {
                blocks.add(BetaContentBlockParam.ofToolUse(BetaToolUseBlockParam.builder()
                        .id(call.id())
                        .name(call.name())
                        .input(readInput(call.input()))
                        .build()));
            }
        }
        return blocks;
    }

    private static BetaToolUseBlockParam.Input readInput(String json) {
        try {
            Map<String, Object> values = ObjectMappers.jsonMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            BetaToolUseBlockParam.Input.Builder input = BetaToolUseBlockParam.Input.builder();
            values.forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
            return input.build();
        } catch (JsonProcessingException e) {
            return BetaToolUseBlockParam.Input.builder().build();
        }
    }

    static List<BetaContentBlockParam> readBlocks(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, BLOCKS);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "stored message is not valid: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Turns the accumulated message into parts, and into the blocks to replay. Before the last
     * fallback switch only text carries over: the declined model's reasoning and tool calls are
     * neither replayed nor run, as the API expects.
     */
    LlmResponse response(BetaMessage message) {
        List<BetaContentBlock> content = message.content();
        int lastSwitch = -1;
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i).isFallback()) {
                lastSwitch = i;
            }
        }
        List<Part> parts = new ArrayList<>();
        List<BetaContentBlockParam> replay = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            BetaContentBlock block = content.get(i);
            boolean beforeSwitch = i < lastSwitch;
            if (block.isText()) {
                parts.add(new Part.Text(block.asText().text()));
                replay.add(block.toParam());
            } else if (block.isToolUse() && !beforeSwitch) {
                parts.add(new Part.ToolCall(block.asToolUse().id(), block.asToolUse().name(), json(block.asToolUse()._input())));
                replay.add(block.toParam());
            } else if ((block.isThinking() || block.isRedactedThinking()) && !beforeSwitch) {
                replay.add(block.toParam());
            }
            // fallback markers and anything else are not part of the replayed conversation
        }
        message.inputTransformations().filter(t -> !t.isEmpty())
                .ifPresent(t -> log.info("Provider dropped {} earlier reasoning block(s) from the conversation", t.size()));
        String refusal = message.stopDetails().flatMap(BetaRefusalStopDetails::category).map(c -> c.asString()).orElse(null);
        return new LlmResponse(parts, new LlmMessage.Native(NATIVE_FORMAT, json(replay)), stopReason(message), refusal,
                usage(message.usage()), message.model().asString());
    }

    private static StopReason stopReason(BetaMessage message) {
        BetaStopReason reason = message.stopReason().orElse(null);
        if (reason == null) {
            return StopReason.OTHER;
        }
        if (reason.equals(BetaStopReason.REFUSAL)) {
            return StopReason.REFUSAL;
        }
        if (reason.equals(BetaStopReason.TOOL_USE)) {
            return StopReason.TOOL_USE;
        }
        if (reason.equals(BetaStopReason.MAX_TOKENS) || reason.equals(BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)) {
            return StopReason.MAX_TOKENS;
        }
        if (reason.equals(BetaStopReason.END_TURN) || reason.equals(BetaStopReason.STOP_SEQUENCE)) {
            return StopReason.END;
        }
        return StopReason.OTHER;
    }

    /** With fallbacks, each attempt is billed: per-attempt usage is the source of truth when present. */
    private static LlmUsage usage(BetaUsage usage) {
        List<BetaUsage.Iteration> iterations = usage.iterations().orElse(List.of());
        if (iterations.isEmpty()) {
            return new LlmUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L),
                    usage.cacheCreationInputTokens().orElse(0L));
        }
        LlmUsage total = LlmUsage.ZERO;
        for (BetaUsage.Iteration iteration : iterations) {
            if (iteration.message().isPresent()) {
                var m = iteration.message().get();
                total = total.plus(new LlmUsage(m.inputTokens(), m.outputTokens(), m.cacheReadInputTokens(), m.cacheCreationInputTokens()));
            } else if (iteration.fallbackMessage().isPresent()) {
                var m = iteration.fallbackMessage().get();
                total = total.plus(new LlmUsage(m.inputTokens(), m.outputTokens(), m.cacheReadInputTokens(), m.cacheCreationInputTokens()));
            }
        }
        return total;
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, e.getOriginalMessage(), e);
        }
    }

    Capabilities capabilities(String model) {
        Instant now = clock.instant();
        CachedCapabilities cached = capabilities.get(model);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.value();
        }
        Capabilities value = lookup(model);
        capabilities.put(model, new CachedCapabilities(value, now.plus(CAPABILITY_TTL)));
        return value;
    }

    private Capabilities lookup(String model) {
        try {
            ModelInfo info = client.models().retrieve(model, ModelRetrieveParams.none(),
                    RequestOptions.builder().timeout(LOOKUP_TIMEOUT).build());
            if (info.capabilities().isPresent()) {
                ModelCapabilities c = info.capabilities().get();
                return new Capabilities(c.thinking().types().adaptive().supported(), c.effort().supported(), c.structuredOutputs().supported());
            }
        } catch (RuntimeException e) {
            log.debug("Capabilities of {} unavailable ({}); guessing from its name", model, e.getMessage());
        }
        return guess(model);
    }

    /** Capabilities by model family, for gateways without the Models API. */
    static Capabilities guess(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        boolean adaptive = ADAPTIVE.matcher(m).matches();
        boolean effort = adaptive || m.contains("opus-4-5");
        boolean structured = m.contains("claude") && !m.contains("claude-3") && !m.matches(".*claude-(opus|sonnet)-4-(0|2025).*");
        return new Capabilities(adaptive, effort, structured);
    }

    @Override
    public List<String> listModels() {
        try {
            List<String> ids = new ArrayList<>();
            for (ModelInfo info : client.models().list().autoPager()) {
                ids.add(info.id());
                if (ids.size() >= 200) {
                    break;
                }
            }
            return ids;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean supportsStructuredOutput(String model) {
        return capabilities(model).structuredOutputs();
    }

    @Override
    public boolean supportsTools(String model) {
        return true;
    }

    private static String problem(AnthropicServiceException e) {
        String body;
        try {
            body = e.body().toString();
        } catch (RuntimeException ignored) {
            body = "";
        }
        return (e.getMessage() + " " + body).toLowerCase(Locale.ROOT);
    }

    static String rejectedFeature(String problem) {
        if (problem.contains("fallback")) {
            return FEATURE_FALLBACKS;
        }
        if (problem.contains("block_binding") || problem.contains("thinking-binding")) {
            return FEATURE_BLOCK_BINDING;
        }
        if (problem.contains("eager_input_streaming")) {
            return FEATURE_EAGER_INPUT;
        }
        if (problem.contains("effort")) {
            return FEATURE_EFFORT;
        }
        if (problem.contains("strict")) {
            return FEATURE_STRICT;
        }
        if (problem.contains("cache_control")) {
            return FEATURE_AUTO_CACHE;
        }
        return null;
    }

    static LlmException translate(RuntimeException e) {
        if (e instanceof LlmException l) {
            return l;
        }
        String detail = e.getMessage();
        LlmException.Kind kind;
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            kind = LlmException.Kind.AUTH;
        } else if (e instanceof RateLimitException) {
            kind = LlmException.Kind.RATE_LIMIT;
        } else if (e instanceof BadRequestException || e instanceof UnprocessableEntityException || e instanceof NotFoundException) {
            kind = LlmException.Kind.BAD_REQUEST;
        } else if (e instanceof InternalServerException || e instanceof SseException) {
            kind = LlmException.Kind.UNAVAILABLE;
        } else if (e instanceof AnthropicServiceException s) {
            kind = s.statusCode() >= 500 ? LlmException.Kind.UNAVAILABLE : LlmException.Kind.BAD_REQUEST;
        } else if (e instanceof AnthropicIoException) {
            kind = hasCause(e, InterruptedIOException.class) ? LlmException.Kind.TIMEOUT : LlmException.Kind.NETWORK;
        } else {
            kind = LlmException.Kind.INVALID_RESPONSE;
        }
        return new LlmException(kind, detail, e);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        client.close();
    }
}
