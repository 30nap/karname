package ir.karname.ai.provider;

import ir.karname.ai.llm.Effort;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.ai.llm.LlmClient;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.StopReason;
import ir.karname.ai.llm.ToolSpec;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.crypto.SecretCipher;
import ir.karname.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Administration of AI providers and of which provider/model serves each task. API keys and
 * extra headers are encrypted at rest and never sent back to the browser.
 */
@Service
public class AiProviderService {

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:/@+-]{1,200}$");
    private static final ToolSpec PING = new ToolSpec("ping", "Confirms the connection works. Call it with the word the user gives.",
            JsonSchema.object(null).required("word", JsonSchema.string("The word to echo")).build());

    private final AiProviderRepository providers;
    private final AiRouteRepository routes;
    private final LlmClients clients;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final KarnameProperties properties;

    public AiProviderService(AiProviderRepository providers, AiRouteRepository routes, LlmClients clients, SecretCipher cipher,
            JsonMapper json, KarnameProperties properties) {
        this.providers = providers;
        this.routes = routes;
        this.clients = clients;
        this.cipher = cipher;
        this.json = json;
        this.properties = properties;
    }

    /** A header to send; a value left empty on update keeps the stored one (values are never sent back). */
    public record HeaderInput(String name, String value) {
    }

    /**
     * @param apiKey a new key; empty keeps the stored key unless {@code clearApiKey}
     * @param useEnvKey read Claude's key from the ANTHROPIC_API_KEY environment variable instead
     */
    public record ProviderRequest(String name, AiPreset preset, String baseUrl, String apiKey, Boolean clearApiKey, Boolean useEnvKey,
            List<HeaderInput> headers, Map<String, String> queryParams, String defaultModel, Boolean supportsTools,
            Boolean supportsJsonSchema, Boolean streamUsage, Boolean refusalFallback, Boolean enabled) {
    }

    public record HeaderView(String name, boolean hasValue) {
    }

    public record ProviderView(long id, String name, AiProviderKind kind, AiPreset preset, String baseUrl, boolean hasApiKey,
            boolean keyFromEnv, List<HeaderView> headers, Map<String, String> queryParams, String defaultModel, boolean supportsTools,
            boolean supportsJsonSchema, boolean streamUsage, boolean refusalFallback, boolean enabled, List<AiTask> usedBy) {
    }

    public record PresetView(AiPreset id, AiProviderKind kind, String label, String baseUrl, String defaultModel, boolean supportsTools,
            boolean supportsJsonSchema, boolean streamUsage, boolean needsKey) {
    }

    public record PresetsView(List<PresetView> presets, boolean envKeyAvailable) {
    }

    public record RouteRequest(AiTask task, Long providerId, String model, Effort effort) {
    }

    public record RouteView(AiTask task, Long providerId, String providerName, String model, Effort effort, Effort defaultEffort) {
    }

    public record TestRequest(ProviderRequest provider, String model) {
    }

    /**
     * @param toolCalling whether the model called a tool when asked (null when tools are off)
     * @param error what went wrong, including the provider's own message
     */
    public record TestResult(boolean ok, long latencyMs, String model, Boolean toolCalling, String reply, String error) {
    }

    /** The provider and model that serve a task, ready to call. */
    public record ResolvedRoute(long providerId, String providerName, AiProviderKind kind, LlmClient client, String model, Effort effort) {
    }

    public PresetsView presets() {
        List<PresetView> list = Arrays.stream(AiPreset.values())
                .filter(p -> p != AiPreset.FAKE || properties.ai().fakeEnabled())
                .map(p -> new PresetView(p, p.kind(), p.label(), p.baseUrl(), p.defaultModel(), p.tools(), p.jsonSchema(), p.streamUsage(),
                        p.needsKey()))
                .toList();
        return new PresetsView(list, StringUtils.hasText(properties.ai().anthropicApiKey()));
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list() {
        List<AiRoute> all = routes.findAll();
        return providers.findAllByOrderByIdAsc().stream().map(p -> view(p, all)).toList();
    }

    @Transactional
    public ProviderView create(ProviderRequest request) {
        AiProvider provider = new AiProvider();
        apply(provider, request);
        if (providers.existsByName(provider.getName())) {
            throw ApiException.conflict("aiProvider.duplicateName");
        }
        return view(providers.saveAndFlush(provider), routes.findAll());
    }

    @Transactional
    public ProviderView update(long id, ProviderRequest request) {
        AiProvider provider = require(id);
        apply(provider, request);
        if (providers.existsByNameAndIdNot(provider.getName(), id)) {
            throw ApiException.conflict("aiProvider.duplicateName");
        }
        AiProvider saved = providers.saveAndFlush(provider);
        clients.evict(id);
        return view(saved, routes.findAll());
    }

    @Transactional
    public void delete(long id) {
        providers.delete(require(id));
        clients.evict(id);
    }

    /** Models offered by a provider, saved or as edited in the form ({@code id} lends stored secrets). */
    public List<String> models(Long id, ProviderRequest request) {
        AiProvider draft = draft(id, request);
        try {
            return clients.create(draft).listModels();
        } catch (LlmException e) {
            throw ApiException.badRequest("aiProvider.modelsFailed", describe(e));
        }
    }

    /**
     * Sends a short request, asking the model to call a tool when tools are on, so the settings
     * are checked end to end before anyone relies on them. Nothing is saved.
     */
    public TestResult test(Long id, TestRequest request) {
        if (request == null || request.provider() == null) {
            throw ApiException.badRequest("error.badRequest");
        }
        AiProvider draft = draft(id, request.provider());
        String model = StringUtils.hasText(request.model()) ? request.model().trim() : draft.getDefaultModel();
        if (!StringUtils.hasText(model) || !MODEL.matcher(model).matches()) {
            throw ApiException.badRequest("aiProvider.modelRequired");
        }
        LlmClient client = clients.create(draft);
        boolean tools = client.supportsTools(model);
        LlmRequest probe = new LlmRequest(model, "You are checking that a connection works. Follow the user's instruction exactly.",
                List.of(new LlmMessage.User(List.of(new Part.Text(tools
                        ? "Call the ping tool with the word \"karname\"."
                        : "Reply with the single word: karname")))),
                tools ? List.of(PING) : List.of(), null, Effort.LOW, 2048);
        long start = System.nanoTime();
        try {
            LlmResponse response = client.send(probe, LlmListener.NONE);
            long latency = (System.nanoTime() - start) / 1_000_000;
            if (response.stopReason() == StopReason.REFUSAL) {
                return new TestResult(false, latency, response.model(), null, null, "مدل درخواست آزمایشی را رد کرد.");
            }
            Boolean toolCalling = tools ? response.toolCalls().stream().anyMatch(c -> c.name().equals(PING.name())) : null;
            String reply = response.text().strip();
            return new TestResult(true, latency, response.model(), toolCalling, reply.length() > 200 ? reply.substring(0, 200) + "…" : reply, null);
        } catch (LlmException e) {
            return new TestResult(false, (System.nanoTime() - start) / 1_000_000, model, null, null, describe(e));
        }
    }

    private static String describe(LlmException e) {
        return e.detail() == null ? e.userMessage() : e.userMessage() + " (" + e.detail() + ")";
    }

    @Transactional(readOnly = true)
    public List<RouteView> routes() {
        Map<Long, String> names = new LinkedHashMap<>();
        providers.findAll().forEach(p -> names.put(p.getId(), p.getName()));
        Map<AiTask, AiRoute> byTask = new LinkedHashMap<>();
        routes.findAll().forEach(r -> byTask.put(r.getTask(), r));
        return Arrays.stream(AiTask.values()).map(task -> {
            AiRoute r = byTask.get(task);
            return r == null
                    ? new RouteView(task, null, null, null, null, task.defaultEffort())
                    : new RouteView(task, r.getProviderId(), names.get(r.getProviderId()), r.getModel(), r.getEffort(), task.defaultEffort());
        }).toList();
    }

    /** Sets every task's route; a task without a provider is switched off. */
    @Transactional
    public List<RouteView> saveRoutes(List<RouteRequest> requests) {
        if (requests == null) {
            throw ApiException.badRequest("aiRoute.invalid");
        }
        for (RouteRequest r : requests) {
            if (r == null || r.task() == null) {
                throw ApiException.badRequest("aiRoute.invalid");
            }
            if (r.providerId() == null) {
                routes.deleteById(r.task());
                continue;
            }
            AiProvider provider = require(r.providerId());
            String model = StringUtils.hasText(r.model()) ? r.model().trim() : provider.getDefaultModel();
            if (!StringUtils.hasText(model) || !MODEL.matcher(model).matches()) {
                throw ApiException.badRequest("aiProvider.modelRequired");
            }
            AiRoute route = routes.findById(r.task()).orElseGet(() -> new AiRoute(r.task()));
            route.setProviderId(provider.getId());
            route.setModel(model);
            route.setEffort(r.effort());
            routes.save(route);
        }
        routes.flush();
        return routes();
    }

    /** The provider and model for a task, if one is set and enabled. */
    @Transactional(readOnly = true)
    public Optional<ResolvedRoute> resolve(AiTask task) {
        return routes.findById(task).flatMap(route -> providers.findById(route.getProviderId())
                .filter(AiProvider::isEnabled)
                .map(p -> new ResolvedRoute(p.getId(), p.getName(), p.getKind(), clients.forProvider(p), route.getModel(),
                        route.getEffort() != null ? route.getEffort() : task.defaultEffort())));
    }

    /** The client of a saved, enabled provider of the given kind (conversations stay on their provider). */
    @Transactional(readOnly = true)
    public Optional<LlmClient> client(Long providerId, AiProviderKind kind) {
        if (providerId == null) {
            return Optional.empty();
        }
        return providers.findById(providerId).filter(p -> p.isEnabled() && p.getKind() == kind).map(clients::forProvider);
    }

    @Transactional(readOnly = true)
    public Optional<String> name(Long providerId) {
        return providerId == null ? Optional.empty() : providers.findById(providerId).map(AiProvider::getName);
    }

    /** A provider as configured in the form, with the stored secrets of {@code id} where none were entered. */
    private AiProvider draft(Long id, ProviderRequest request) {
        AiProvider draft = new AiProvider();
        if (id != null) {
            AiProvider stored = require(id);
            // the stored address too, so a changed one is noticed and the secrets are not lent to it
            draft.setBaseUrl(stored.getBaseUrl());
            draft.setApiKeyEncrypted(stored.getApiKeyEncrypted());
            draft.setHeadersEncrypted(stored.getHeadersEncrypted());
            draft.setKeyFromEnv(stored.isKeyFromEnv());
        }
        apply(draft, request);
        return draft;
    }

    private void apply(AiProvider p, ProviderRequest r) {
        if (r == null || r.preset() == null) {
            throw ApiException.badRequest("aiProvider.invalidPreset");
        }
        if (r.preset() == AiPreset.FAKE && !properties.ai().fakeEnabled()) {
            throw ApiException.badRequest("aiProvider.fakeDisabled");
        }
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiException.badRequest("aiProvider.invalidName");
        }
        AiProviderKind kind = r.preset().kind();
        String baseUrl = StringUtils.hasText(r.baseUrl()) ? r.baseUrl().strip() : r.preset().baseUrl();
        if (kind == AiProviderKind.FAKE) {
            baseUrl = "fake://local";
        } else if (!validUrl(baseUrl)) {
            throw ApiException.badRequest("aiProvider.invalidUrl");
        }
        String model = StringUtils.hasText(r.defaultModel()) ? r.defaultModel().strip() : r.preset().defaultModel();
        if (model != null && !MODEL.matcher(model).matches()) {
            throw ApiException.badRequest("aiProvider.invalidModel");
        }
        // stored secrets only ever go to the address they were entered for
        boolean moved = p.getBaseUrl() != null && !sameAddress(p.getBaseUrl(), baseUrl);
        if (moved && p.getApiKeyEncrypted() != null && !StringUtils.hasText(r.apiKey()) && !Boolean.TRUE.equals(r.clearApiKey())) {
            throw ApiException.badRequest("aiProvider.keyForNewUrl");
        }
        p.setName(name);
        p.setPreset(r.preset());
        p.setKind(kind);
        p.setBaseUrl(baseUrl);
        p.setDefaultModel(model);
        applyKey(p, r, kind);
        if (p.isKeyFromEnv() && !sameAddress(baseUrl, AiPreset.ANTHROPIC.baseUrl())) {
            throw ApiException.badRequest("aiProvider.envKeyOfficialOnly");
        }
        Map<String, String> headers = mergedHeaders(p, r.headers(), moved);
        p.setHeadersEncrypted(headers.isEmpty() ? null : cipher.encrypt(json.writeValueAsString(headers)));
        Map<String, String> params = queryParams(r.queryParams());
        p.setQueryParams(params.isEmpty() ? null : json.writeValueAsString(params));
        p.setSupportsTools(r.supportsTools() != null ? r.supportsTools() : r.preset().tools());
        p.setSupportsJsonSchema(r.supportsJsonSchema() != null ? r.supportsJsonSchema() : r.preset().jsonSchema());
        p.setStreamUsage(r.streamUsage() != null ? r.streamUsage() : r.preset().streamUsage());
        p.setRefusalFallback(r.refusalFallback() == null || r.refusalFallback());
        p.setEnabled(r.enabled() == null || r.enabled());
    }

    private void applyKey(AiProvider p, ProviderRequest r, AiProviderKind kind) {
        if (Boolean.TRUE.equals(r.useEnvKey())) {
            if (kind != AiProviderKind.ANTHROPIC) {
                throw ApiException.badRequest("aiProvider.envKeyAnthropicOnly");
            }
            p.setKeyFromEnv(true);
            p.setApiKeyEncrypted(null);
            return;
        }
        if (StringUtils.hasText(r.apiKey())) {
            String key = r.apiKey().strip();
            if (key.length() > 4000 || key.contains("\n") || key.contains("\r")) {
                throw ApiException.badRequest("aiProvider.invalidKey");
            }
            p.setApiKeyEncrypted(cipher.encrypt(key));
            p.setKeyFromEnv(false);
        } else if (Boolean.TRUE.equals(r.clearApiKey()) || Boolean.FALSE.equals(r.useEnvKey()) && p.isKeyFromEnv()) {
            p.setApiKeyEncrypted(null);
            p.setKeyFromEnv(false);
        }
    }

    private static boolean sameAddress(String a, String b) {
        return a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }

    private Map<String, String> mergedHeaders(AiProvider p, List<HeaderInput> input, boolean moved) {
        Map<String, String> stored = clients.headers(p);
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        if (input.size() > 10) {
            throw ApiException.badRequest("aiProvider.invalidHeaders");
        }
        for (HeaderInput h : input) {
            String name = h == null || h.name() == null ? "" : h.name().strip();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw ApiException.badRequest("aiProvider.invalidHeaders");
            }
            boolean kept = h.value() == null || h.value().isEmpty();
            if (kept && moved && stored.containsKey(name)) {
                throw ApiException.badRequest("aiProvider.keyForNewUrl");
            }
            String value = kept ? stored.get(name) : h.value().strip();
            if (value == null || value.length() > 4000 || value.contains("\n") || value.contains("\r")) {
                throw ApiException.badRequest("aiProvider.invalidHeaders");
            }
            result.put(name, value);
        }
        return result;
    }

    private static Map<String, String> queryParams(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        if (input.size() > 10) {
            throw ApiException.badRequest("aiProvider.invalidQuery");
        }
        input.forEach((name, value) -> {
            String n = name == null ? "" : name.strip();
            String v = value == null ? "" : value.strip();
            if (!PARAM_NAME.matcher(n).matches() || v.isEmpty() || v.length() > 500) {
                throw ApiException.badRequest("aiProvider.invalidQuery");
            }
            result.put(n, v);
        });
        return result;
    }

    private static boolean validUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > 500) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private ProviderView view(AiProvider p, List<AiRoute> allRoutes) {
        List<HeaderView> headers = clients.headers(p).keySet().stream().map(name -> new HeaderView(name, true)).toList();
        List<AiTask> usedBy = allRoutes.stream().filter(r -> r.getProviderId().equals(p.getId())).map(AiRoute::getTask).sorted().toList();
        return new ProviderView(p.getId(), p.getName(), p.getKind(), p.getPreset(), p.getBaseUrl(),
                p.getApiKeyEncrypted() != null || (p.isKeyFromEnv() && StringUtils.hasText(properties.ai().anthropicApiKey())), p.isKeyFromEnv(),
                headers, clients.queryParams(p), p.getDefaultModel(), p.isSupportsTools(), p.isSupportsJsonSchema(), p.isStreamUsage(),
                p.isRefusalFallback(), p.isEnabled(), usedBy);
    }

    private AiProvider require(long id) {
        return providers.findById(id).orElseThrow(() -> ApiException.notFound("aiProvider.notFound"));
    }
}
