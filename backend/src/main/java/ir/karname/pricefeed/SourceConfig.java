package ir.karname.pricefeed;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** "Bearer abc", "Token abc", "Basic dXNlcjpwYXNz": a scheme and the credential after it. */
    private static final Pattern SCHEME = Pattern.compile("(?i)^(Bearer|Token|Basic|Bot|Key|ApiKey|Api-Key)\\s+(\\S+)$");

    /** Values that must never appear in an error message: header values, the credential in them, the key. */
    List<String> secrets() {
        List<String> result = new ArrayList<>();
        for (String value : requestHeaders().values()) {
            result.add(value);
            Matcher scheme = SCHEME.matcher(value.strip());
            if (scheme.matches()) {
                String credential = scheme.group(2);
                result.add(credential);
                if (scheme.group(1).equalsIgnoreCase("Basic")) {
                    try {
                        String pair = new String(Base64.getDecoder().decode(credential), StandardCharsets.UTF_8);
                        result.add(pair);
                        result.add(pair.substring(pair.indexOf(':') + 1));
                    } catch (IllegalArgumentException e) {
                        // not Base64: the credential itself is already listed
                    }
                }
            }
        }
        if (apiKey != null) {
            result.add(apiKey);
        }
        return result;
    }
}
