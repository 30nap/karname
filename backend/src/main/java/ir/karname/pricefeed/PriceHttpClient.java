package ir.karname.pricefeed;

import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * GETs a price API with short timeouts and a size cap. Honours the JVM proxy settings
 * ({@code https.proxyHost}…), which servers in Iran often need for some sources.
 */
@Component
class PriceHttpClient {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // a redirect could carry the key headers to another host, or reach an address the settings would refuse
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault())
            .build();

    String get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
                .header("Accept", "application/json")
                .header("User-Agent", "Karname/1.0 (+self-hosted personal finance)");
        headers.forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw new PriceFetchException("سرویس با کد HTTP " + PersianText.toPersianDigits(String.valueOf(response.statusCode())) + " پاسخ داد.");
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new PriceFetchException("پاسخ سرویس بیش از ۲ مگابایت است.");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (HttpTimeoutException e) {
            throw new PriceFetchException("سرویس در زمان مقرر پاسخ نداد.", e);
        } catch (IOException e) {
            throw new PriceFetchException("اتصال به سرویس برقرار نشد (" + e.getClass().getSimpleName() + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceFetchException("دریافت قیمت متوقف شد.", e);
        }
    }
}
