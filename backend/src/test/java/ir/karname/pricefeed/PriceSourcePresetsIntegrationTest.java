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
                     {"symbol": "ETH_IRT", "price": "0", "daily_change_price": 0, "low": "0", "high": "0", "timestamp": 1722860054.189}]""",
            // TGJU ajax.json: captured 2026-08-31 (dollar, euro, gold, coins, Tether) and 2026-09-10 (pound, dirham, lira,
            // yuan, Bahar coin); Canadian and Australian dollar from a dump of 2026-10-12. Rial, strings with commas; usdt-irr
            // is the dead slug, frozen since 2020
            "/ajax.json", """
                    {"current": {
                      "price_dollar_rl": {"p": "2,092,950", "h": "2,099,200", "l": "2,067,600", "d": "32,850", "dp": 1.59, "dt": "high",
                        "t": "۱۹:۳۳:۲۲", "t_en": "19:33:22", "ts": "2026-08-31 19:33:22"},
                      "price_eur": {"p": "2,439,800", "ts": "2026-08-31 19:33:10"},
                      "price_gbp": {"p": "3,195,400", "h": "3,202,400", "l": "3,177,200", "ts": "2026-09-10 19:30:02"},
                      "price_aed": {"p": "646,530", "ts": "2026-09-10 19:30:02"},
                      "price_try": {"p": "49,600", "ts": "2026-09-10 19:30:02"},
                      "price_cad": {"p": "1,852,900", "ts": "2026-10-12 12:00:00"},
                      "price_aud": {"p": "1,842,600", "ts": "2026-10-12 12:00:00"},
                      "price_cny": {"p": "355,300", "ts": "2026-09-10 19:30:02"},
                      "geram18": {"p": "221,838,000", "ts": "2026-08-31 19:33:17"},
                      "geram24": {"p": "295,782,000", "ts": "2026-08-31 19:33:17"},
                      "mesghal": {"p": "960,960,000", "h": "968,550,000", "ts": "2026-08-31 19:33:17"},
                      "sekee": {"p": "2,235,100,000", "h": "2,235,100,000", "l": "2,194,800,000", "d": "55,000,000", "dp": 2.52, "dt": "high",
                        "t": "۱۹:۳۳:۲۰", "t_en": "19:33:20", "t-g": "۱۹:۳۳:۲۰", "ts": "2026-08-31 19:33:20"},
                      "sekeb": {"p": "2,363,550,000", "d": "0", "dp": 0, "dt": "", "t": "۱۹ شهریور", "t_en": "10 Sep", "ts": "2026-09-10 00:00:00"},
                      "nim": {"p": "1,140,000,000", "ts": "2026-08-31 19:33:20"},
                      "rob": {"p": "615,000,000", "ts": "2026-08-31 19:33:20"},
                      "gerami": {"p": "320,000,000", "ts": "2026-08-31 14:45:26"},
                      "crypto-tether-irr": {"p": "2,096,430", "h": "2,096,600", "l": "2,065,940", "ts": "2026-08-31 19:33:21"},
                      "usdt-irr": {"p": "273000", "h": "274000", "l": "270000", "d": "0", "dp": 0, "dt": "", "t": "۲۱ آبان", "ts": "2020-11-11 17:00:00"}
                    }, "last": [{"name": "usd-nzd-ask", "item_id": 136816, "p": "1.7199"}]}""",
            // Navasan /latest/: every item keyed by code, {value, change, timestamp, date}, values as text in Toman, coins and
            // melted gold in thousands of Toman. Dollar, 18k gold and Emami coin as reported for 1405-05-31; the rest are
            // illustrative figures in the same shape
            "/latest/", """
                    {"usd_sell": {"value": "192600", "change": -400, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "usd_buy": {"value": "192400", "change": -400, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "eur": {"value": "224500", "change": 300, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "gbp": {"value": "259800", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "aed": {"value": "52480", "change": -100, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "try": {"value": "4710", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "cad": {"value": "139900", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "aud": {"value": "125300", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "cny": {"value": "26800", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "18ayar": {"value": "20961260", "change": 52000, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "abshodeh": {"value": "90820", "change": 210, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "sekkeh": {"value": "209000", "change": 1500, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "bahar": {"value": "198500", "change": 1000, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "nim": {"value": "112000", "change": 500, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "rob": {"value": "67500", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "gerami": {"value": "33000", "change": 0, "timestamp": 1787395800, "date": "1405-05-31 14:00:00"},
                     "dollar_rate": "192600"}""");

    /** Expected Toman price, or the start of the error, per preset and commodity. */
    static final Map<String, Map<String, String>> EXPECTED = Map.of(
            "NOBITEX", Map.of("USDT", "209423", "BTC", "16357002003", "ETH", "514905997", "TON", "!بازار gram-rls"),
            "WALLEX", Map.of("USDT", "209800", "BTC", "16439499999", "ETH", "!مقدار «-»", "TON", "!مسیر /result/symbols/GRAMTMN"),
            "BITPIN", Map.of("USDT", "267747", "BTC", "3145737420", "ETH", "!مقدار «0»"),
            "TGJU", Map.ofEntries(Map.entry("USD", "209295"), Map.entry("EUR", "243980"), Map.entry("GBP", "319540"),
                    Map.entry("AED", "64653"), Map.entry("TRY", "4960"), Map.entry("CAD", "185290"), Map.entry("AUD", "184260"),
                    Map.entry("CNY", "35530"), Map.entry("GOLD18", "22183800"), Map.entry("GOLD24", "29578200"),
                    Map.entry("MESGHAL", "96096000"), Map.entry("COIN_EMAMI", "223510000"), Map.entry("COIN_BAHAR", "236355000"),
                    Map.entry("COIN_HALF", "114000000"), Map.entry("COIN_QUARTER", "61500000"), Map.entry("COIN_GERAMI", "32000000"),
                    Map.entry("USDT", "209643")),
            "NAVASAN", Map.ofEntries(Map.entry("USD", "192600"), Map.entry("EUR", "224500"), Map.entry("GBP", "259800"),
                    Map.entry("AED", "52480"), Map.entry("TRY", "4710"), Map.entry("CAD", "139900"), Map.entry("AUD", "125300"),
                    Map.entry("CNY", "26800"), Map.entry("GOLD18", "20961260"), Map.entry("MESGHAL", "90820000"),
                    Map.entry("COIN_EMAMI", "209000000"), Map.entry("COIN_BAHAR", "198500000"), Map.entry("COIN_HALF", "112000000"),
                    Map.entry("COIN_QUARTER", "67500000"), Map.entry("COIN_GERAMI", "33000000")));

    private static HttpServer server;
    private static String base;

    @Autowired
    private PriceFeedService feeds;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ANSWERS.forEach((path, body) -> server.createContext(path, exchange -> {
            // a preset with a key must send it where the service expects it
            boolean refused = path.equals("/latest/") && !"api_key=test-key".equals(exchange.getRequestURI().getRawQuery());
            byte[] bytes = (refused ? "{\"message\": \"invalid api_key\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(refused ? 401 : 200, bytes.length);
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
        // the address with {key} is not a URI yet: swap the scheme and host as text
        String url = preset.kind() == PriceSourceKind.NOBITEX ? base : preset.url().replaceFirst("^https://[^/]+", base);
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
