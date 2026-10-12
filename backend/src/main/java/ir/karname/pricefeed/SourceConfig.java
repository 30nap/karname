package ir.karname.pricefeed;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a provider needs to read a source, with secrets decrypted. {@code {key}} in the
 * address or a header value stands for the API key, which is stored apart and filled in only
 * when the request is made.
 */
public record SourceConfig(PriceSourceKind kind, String url, Map<String, String> headers, PriceUnit unit, List<PriceMapping> mappings,
        String apiKey) {

    public static final String KEY = "{key}";

    public SourceConfig(PriceSourceKind kind, String url, Map<String, String> headers, PriceUnit unit, List<PriceMapping> mappings) {
        this(kind, url, headers, unit, mappings, null);
    }

    /** An address with {@code {key}} replaced by the URL-encoded key. */
    URI uri(String address) {
        String key = apiKey == null ? "" : URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return URI.create(address.replace(KEY, key));
    }

    Map<String, String> requestHeaders() {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> result.put(name, apiKey == null ? value : value.replace(KEY, apiKey)));
        return result;
    }

    /** Values that must never appear in an error message. */
    List<String> secrets() {
        List<String> result = new ArrayList<>(requestHeaders().values());
        if (apiKey != null) {
            result.add(apiKey);
        }
        return result;
    }
}
