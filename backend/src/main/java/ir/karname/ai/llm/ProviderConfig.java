package ir.karname.ai.llm;

import java.util.Map;

/**
 * Connection settings of one provider.
 *
 * @param apiKey may be empty for local servers or gateways that authenticate by header
 * @param tools whether the provider's models can call tools (OpenAI-compatible servers vary)
 * @param jsonSchema whether the provider accepts a JSON schema for the reply
 * @param streamUsage whether to ask an OpenAI-compatible stream for token usage
 * @param refusalFallback whether a declined request may be answered by a fallback model
 */
public record ProviderConfig(String baseUrl, String apiKey, Map<String, String> headers, Map<String, String> queryParams, boolean tools,
        boolean jsonSchema, boolean streamUsage, boolean refusalFallback) {

    public ProviderConfig {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    }
}
