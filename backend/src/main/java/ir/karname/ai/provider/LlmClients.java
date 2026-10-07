package ir.karname.ai.provider;

import ir.karname.ai.llm.LlmClient;
import ir.karname.ai.llm.ProviderConfig;
import ir.karname.ai.llm.anthropic.AnthropicLlmClient;
import ir.karname.ai.llm.fake.FakeLlm;
import ir.karname.ai.llm.fake.FakeLlmClient;
import ir.karname.ai.llm.openai.OpenAiCompatibleLlmClient;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.crypto.SecretCipher;
import ir.karname.common.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the client for a provider's settings, decrypting its secrets; one client per provider
 * is kept until the provider changes (its version moves).
 */
@Component
public class LlmClients {

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private record Cached(long version, LlmClient client) {
    }

    private final SecretCipher cipher;
    private final JsonMapper json;
    private final KarnameProperties properties;
    private final ObjectProvider<FakeLlm> fake;
    private final Map<Long, Cached> clients = new ConcurrentHashMap<>();

    public LlmClients(SecretCipher cipher, JsonMapper json, KarnameProperties properties, ObjectProvider<FakeLlm> fake) {
        this.cipher = cipher;
        this.json = json;
        this.properties = properties;
        this.fake = fake;
    }

    public LlmClient forProvider(AiProvider provider) {
        Cached cached = clients.get(provider.getId());
        if (cached != null && cached.version() == provider.getVersion()) {
            return cached.client();
        }
        // an older client may still be serving a request: it is left to finish, not closed
        LlmClient client = create(provider);
        clients.put(provider.getId(), new Cached(provider.getVersion(), client));
        return client;
    }

    /** A fresh client, not cached: for testing settings that are not saved yet. */
    public LlmClient create(AiProvider provider) {
        ProviderConfig config = config(provider);
        return switch (provider.getKind()) {
            case ANTHROPIC -> new AnthropicLlmClient(config);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlmClient(config);
            case FAKE -> {
                FakeLlm script = fake.getIfAvailable();
                if (script == null) {
                    throw ApiException.badRequest("aiProvider.fakeDisabled");
                }
                yield new FakeLlmClient(script, config);
            }
        };
    }

    public void evict(long providerId) {
        clients.remove(providerId);
    }

    ProviderConfig config(AiProvider p) {
        String key = p.isKeyFromEnv() ? properties.ai().anthropicApiKey() : cipher.decrypt(p.getApiKeyEncrypted());
        return new ProviderConfig(p.getBaseUrl(), key, headers(p), queryParams(p), p.isSupportsTools(), p.isSupportsJsonSchema(),
                p.isStreamUsage(), p.isRefusalFallback());
    }

    Map<String, String> headers(AiProvider p) {
        return p.getHeadersEncrypted() == null ? Map.of() : read(cipher.decrypt(p.getHeadersEncrypted()), p);
    }

    Map<String, String> queryParams(AiProvider p) {
        return p.getQueryParams() == null ? Map.of() : read(p.getQueryParams(), p);
    }

    private Map<String, String> read(String text, AiProvider p) {
        try {
            return json.readValue(text, STRING_MAP);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored settings of AI provider " + p.getId() + " are not valid JSON", e);
        }
    }
}
