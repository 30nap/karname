package ir.karname.pricefeed;

import com.sun.net.httpserver.HttpServer;
import ir.karname.pricefeed.PriceFeedService.RunResult;
import ir.karname.pricefeed.PriceFeedService.SourceRequest;
import ir.karname.pricefeed.PriceSourcePresets.Preset;
import ir.karname.support.FinanceTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every preset against answers in the shape its service really gives: samples captured from the
 * live services (trimmed) and quoted from their documentation, served locally. A preset whose paths
 * or units drift from these shapes fails here.
 */
class PriceSourcePresetsIntegrationTest extends FinanceTestSupport {

    /** Path of each service's answer, and the answer. */
    static final Map<String, String> ANSWERS = Map.of(
            // Nobitex, captured 2026-08-31: Rial, numbers as strings
            "/market/stats", """
                    {"status": "ok", "stats": {
                      "usdt-rls": {"isClosed": false, "bestSell": "2094990", "bestBuy": "2094220", "latest": "2094230", "mark": "2094600"},
                      "btc-rls": {"isClosed": false, "bestSell": "163580000000", "latest": "163570020030", "mark": "163600000000"},
                      "eth-rls": {"isClosed": false, "bestSell": "5149059970", "bestBuy": "5136903740", "latest": "5149059970", "mark": "5173117400"}
                    }, "global": {"binance": {}}}""",
            // Wallex /v1/markets, captured 2026-08-31: Toman with sixteen decimals, keyed by symbol
            "/v1/markets", """
                    {"result": {"symbols": {
                      "USDTTMN": {"symbol": "USDTTMN", "quoteAsset": "TMN", "stats": {"bidPrice": "209751.0000000000000000",
                        "askPrice": "209800.0000000000000000", "lastPrice": "209800.0000000000000000", "24h_ch": 1.96}},
                      "BTCTMN": {"symbol": "BTCTMN", "quoteAsset": "TMN", "stats": {"lastPrice": "16439499999.0000000000000000"}},
                      "ETHTMN": {"symbol": "ETHTMN", "quoteAsset": "TMN", "stats": {"lastPrice": "-"}}
                    }}, "success": true, "message": "ok"}""",
            // Bitpin /api/v1/mkt/tickers/: a bare list, IRT markets in Toman, many rows at zero
            "/api/v1/mkt/tickers/", """
                    [{"symbol": "BTC_USDT", "price": "78389.32", "daily_change_price": 1.9499, "low": "77000", "high": "79000", "timestamp": 1791027000.5},
                     {"symbol": "USDT_IRT", "price": "267747", "daily_change_price": 0.4, "low": "262910", "high": "270448", "timestamp": 1791027000.5},
                     {"symbol": "BTC_IRT", "price": "3145737420", "daily_change_price": -15.58, "low": "3035557311", "high": "3745700550", "timestamp": 1722860054.189},
                     {"symbol": "ETH_IRT", "price": "0", "daily_change_price": 0, "low": "0", "high": "0", "timestamp": 1722860054.189}]""");

    /** Expected Toman price, or the start of the error, per preset and commodity. */
    static final Map<String, Map<String, String>> EXPECTED = Map.of(
            "NOBITEX", Map.of("USDT", "209423", "BTC", "16357002003", "ETH", "514905997", "TON", "!بازار gram-rls"),
            "WALLEX", Map.of("USDT", "209800", "BTC", "16439499999", "ETH", "!مقدار «-»", "TON", "!مسیر /result/symbols/GRAMTMN"),
            "BITPIN", Map.of("USDT", "267747", "BTC", "3145737420", "ETH", "!مقدار «0»"));

    private static HttpServer server;
    private static String base;

    @Autowired
    private PriceFeedService feeds;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ANSWERS.forEach((path, body) -> server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    /** The preset as the settings form would send it, aimed at the local server instead of the service. */
    private static SourceRequest local(Preset preset) {
        URI real = URI.create(preset.url());
        String url = preset.kind() == PriceSourceKind.NOBITEX ? base : base + real.getRawPath() + (real.getRawQuery() == null ? "" : "?" + real.getRawQuery());
        List<PriceFeedService.HeaderInput> headers = preset.headers().entrySet().stream()
                .map(e -> new PriceFeedService.HeaderInput(e.getKey(), e.getValue())).toList();
        return new SourceRequest(preset.name(), preset.kind(), url, preset.unit(), headers, preset.mappings(), preset.intervalMinutes(), false,
                preset.requiresKey() ? "test-key" : null, preset.id());
    }

    @Test
    void everyPresetReadsItsServicesAnswer() {
        assertThat(PriceSourcePresets.all()).extracting(Preset::id).containsAll(EXPECTED.keySet());
        for (Preset preset : PriceSourcePresets.all()) {
            Map<String, String> expected = EXPECTED.get(preset.id());
            assertThat(expected).as("expectations for preset " + preset.id()).isNotNull();
            RunResult run = feeds.test(null, local(preset));
            assertThat(run.error()).as(preset.id()).isNull();
            Map<String, String> actual = new LinkedHashMap<>();
            run.results().forEach(r -> actual.put(r.commodity(), r.ok() ? r.priceToman().stripTrailingZeros().toPlainString() : "!" + r.error()));
            assertThat(actual.keySet()).as(preset.id()).containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((code, value) -> {
                if (value.startsWith("!")) {
                    assertThat(actual.get(code)).as(preset.id() + " " + code).startsWith(value);
                } else {
                    assertThat(actual.get(code)).as(preset.id() + " " + code).isEqualTo(value);
                }
            });
        }
    }
}
