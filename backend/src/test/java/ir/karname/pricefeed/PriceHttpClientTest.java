package ir.karname.pricefeed;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceHttpClientTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void aServerThatStopsSendingTheBodyDoesNotHoldTheFetchForever() throws IOException {
        String url = serve("/slow", exchange -> {
            // the headers and a little of the body, then nothing
            exchange.sendResponseHeaders(200, 1000);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"usd\":".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        PriceHttpClient http = new PriceHttpClient();
        http.deadline = Duration.ofMillis(800);
        long start = System.nanoTime();
        assertThatThrownBy(() -> http.get(URI.create(url), Map.of(), List.of()))
                .isInstanceOf(PriceFetchException.class)
                .hasMessage("سرویس در زمان مقرر پاسخ نداد.");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aHeaderTheHttpClientRefusesIsReportedWithoutItsValue() {
        PriceHttpClient http = new PriceHttpClient();
        assertThatThrownBy(() -> http.get(URI.create("http://127.0.0.1:1/x"), Map.of("Authorization", "Token s3cr3t‏"), List.of()))
                .isInstanceOf(PriceFetchException.class)
                .message().doesNotContain("s3cr3t").startsWith("سرآیندها یا نشانی درخواست پذیرفته نشد");
    }

    @Test
    void anAddressTheSettingsWouldRefuseIsNotAsked() {
        PriceHttpClient http = new PriceHttpClient();
        assertThatThrownBy(() -> http.get(URI.create("http://169.254.169.254/latest/meta-data/"), Map.of(), List.of()))
                .isInstanceOf(PriceFetchException.class)
                .hasMessage("این نشانی برای منبع قیمت مجاز نیست.");
    }

    @Test
    void credentialsAfterAnAuthSchemeAreRedacted() {
        SourceConfig config = new SourceConfig(PriceSourceKind.JSON, "https://example.com", Map.of("Authorization", "Bearer abc123token",
                "X-Basic", "Basic dXNlcjpwYXNzd29yZA=="), PriceUnit.TOMAN, List.of());
        assertThat(PriceHttpClient.detail("{\"message\": \"Invalid token abc123token for user:password, password\"}", config.secrets()))
                .isEqualTo("Invalid token ••• for •••, •••");
        // control characters (a NUL from a compressed body) are folded away and cannot reach the database
        assertThat(PriceHttpClient.detail("bad\u0000 abc123token", config.secrets())).isEqualTo("bad •••");
    }
}
