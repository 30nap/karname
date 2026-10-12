package ir.karname.pricefeed;

import ir.karname.common.OutboundUrls;
import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GETs a price API with short timeouts and a size cap. Honours the JVM proxy settings
 * ({@code https.proxyHost}…), which servers in Iran often need for some sources. A failed answer
 * is reported with the reason the service gave, and never with the API key in it.
 */
@Component
class PriceHttpClient {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final String USER_AGENT = "Karname/1.0 (+self-hosted personal finance)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /**
     * The whole exchange, body included: the request timeout ends once the headers arrive, and a
     * server that then stops sending would hold the scheduler forever.
     */
    Duration deadline = Duration.ofSeconds(30);
    private static final int DETAIL_LENGTH = 160;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // a redirect could carry the key headers to another host, or reach an address the settings would refuse
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault())
            .build();

    /** @param secrets values that must not appear in an error message (API keys, header values) */
    String get(URI uri, Map<String, String> headers, Collection<String> secrets) {
        // the address as asked, with the key in it: the settings only saw it with a stand-in
        if (!OutboundUrls.isAllowed(uri.toString())) {
            throw new PriceFetchException("این نشانی برای منبع قیمت مجاز نیست.");
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT);
            // a source may need its own User-Agent or Accept: configured headers replace the defaults
            headers.forEach(builder::setHeader);
            request = builder.build();
        } catch (IllegalArgumentException e) {
            // the JDK's message quotes the header value, which may be the key
            throw new PriceFetchException("سرآیندها یا نشانی درخواست پذیرفته نشد؛ آن‌ها را در تنظیمات منبع دوباره بنویسید.");
        }
        AtomicReference<InputStream> open = new AtomicReference<>();
        CompletableFuture<String> answer = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("price-fetch").start(() -> {
            try {
                answer.complete(exchange(request, secrets, open));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        try {
            return answer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abort(worker, open);
            throw new PriceFetchException("سرویس در زمان مقرر پاسخ نداد.", e);
        } catch (InterruptedException e) {
            abort(worker, open);
            Thread.currentThread().interrupt();
            throw new PriceFetchException("دریافت قیمت متوقف شد.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PriceFetchException("اتصال به سرویس برقرار نشد (" + e.getCause().getClass().getSimpleName() + ").", e.getCause());
        }
    }

    private static void abort(Thread worker, AtomicReference<InputStream> open) {
        worker.interrupt();
        InputStream body = open.get();
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // closing only to free the connection
            }
        }
    }

    private String exchange(HttpRequest request, Collection<String> secrets, AtomicReference<InputStream> open) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                open.set(body);
                int status = response.statusCode();
                if (status / 100 == 3) {
                    String location = response.headers().firstValue("Location").map(l -> redact(l, secrets)).orElse("");
                    throw new PriceFetchException("سرویس به نشانی دیگری هدایت کرد (کد " + digits(status) + ")"
                            + (location.isBlank() ? "." : ": " + shorten(location)) + " نشانی تازه را در تنظیمات منبع بنویسید.", status);
                }
                if (status / 100 != 2) {
                    String detail = detail(new String(body.readNBytes(16 * 1024), StandardCharsets.UTF_8), secrets);
                    throw new PriceFetchException("سرویس با کد HTTP " + digits(status) + " پاسخ داد" + (detail.isEmpty() ? "." : ": " + detail), status);
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new PriceFetchException("پاسخ سرویس بیش از ۲ مگابایت است.");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (HttpConnectTimeoutException e) {
            throw new PriceFetchException("اتصال به سرویس در زمان مقرر برقرار نشد.", e);
        } catch (HttpTimeoutException e) {
            throw new PriceFetchException("سرویس در زمان مقرر پاسخ نداد.", e);
        } catch (UnknownHostException e) {
            throw new PriceFetchException("نام سرویس پیدا نشد (DNS)؛ نشانی و دسترسی سرور به اینترنت را بررسی کنید.", e);
        } catch (ConnectException e) {
            throw new PriceFetchException("اتصال به سرویس برقرار نشد؛ شاید سرویس از دسترس سرور خارج است.", e);
        } catch (SSLException e) {
            throw new PriceFetchException("اتصال امن (TLS) به سرویس برقرار نشد.", e);
        } catch (IOException e) {
            throw new PriceFetchException("اتصال به سرویس برقرار نشد (" + e.getClass().getSimpleName() + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceFetchException("دریافت قیمت متوقف شد.", e);
        }
    }

    /** The service's own explanation from an error body: a JSON message, an HTML title, or the start of the text. */
    static String detail(String body, Collection<String> secrets) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return "";
        }
        String message = null;
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                message = jsonMessage(MAPPER.readTree(text));
            } catch (JacksonException e) {
                // not JSON after all: fall back to the text
            }
        } else if (text.startsWith("<")) {
            Matcher title = HTML_TITLE.matcher(text);
            message = title.find() ? title.group(1) : text.replaceAll("(?s)<[^>]*>", " ");
        }
        if (message == null) {
            message = text;
        }
        // redacted before and after the spaces are folded, so a secret matches in either form; control
        // characters (a compressed body, a NUL) cannot be stored
        String folded = redact(message, secrets).replaceAll("[\\s\\p{Cc}]+", " ").strip();
        return shorten(redact(folded, secrets));
    }

    private static String jsonMessage(JsonNode root) {
        JsonNode node = root.isArray() && !root.isEmpty() ? root.get(0) : root;
        for (String field : new String[] {"message", "detail", "error", "msg", "error_description", "title", "code"}) {
            JsonNode value = node.path(field);
            if (value.isString() && !value.asString().isBlank()) {
                return value.asString();
            }
            if (value.isObject() && value.path("message").isString()) {
                return value.path("message").asString();
            }
        }
        JsonNode errors = node.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            JsonNode first = errors.get(0);
            return first.isString() ? first.asString() : first.path("message").asString(null);
        }
        if (errors.isObject()) {
            return errors.toString();
        }
        return node.toString();
    }

    static String redact(String text, Collection<String> secrets) {
        String result = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                result = result.replace(secret, "•••");
                String encoded = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
                if (!encoded.equals(secret)) {
                    result = result.replace(encoded, "•••");
                }
            }
        }
        return result;
    }

    private static String shorten(String text) {
        return text.length() > DETAIL_LENGTH ? text.substring(0, DETAIL_LENGTH) + "…" : text;
    }

    private static String digits(int status) {
        return PersianText.toPersianDigits(String.valueOf(status));
    }
}
