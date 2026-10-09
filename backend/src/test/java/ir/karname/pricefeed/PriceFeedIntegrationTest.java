package ir.karname.pricefeed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Price sources against a local HTTP server that plays Nobitex and a generic JSON API. */
class PriceFeedIntegrationTest extends FinanceTestSupport {

    private static HttpServer server;
    private static String base;
    private static final List<String> requests = new CopyOnWriteArrayList<>();
    private static volatile String usdtRial = "1040000";

    @Autowired
    private PriceFeedService feeds;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/stats", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            respond(exchange, 200, """
                    {"status": "ok", "stats": {
                      "usdt-rls": {"isClosed": false, "bestSell": "1041000", "latest": "%s", "mark": "1039000"},
                      "btc-rls": {"isClosed": false, "bestSell": "75100000000", "latest": "75000000000"},
                      "eth-rls": {"isClosed": true, "bestSell": "2900000000", "mark": "2880000000"}
                    }}""".formatted(usdtRial));
        });
        server.createContext("/gold.json", exchange -> {
            if (!"secret".equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                respond(exchange, 401, "{\"error\": \"unauthorized\"}");
                return;
            }
            respond(exchange, 200, """
                    {"data": {"gold18": {"price": "۸۹٬۵۰۰٬۰۰۰"}, "coin": {"emami": 980000000, "half": "n/a"}}}""");
        });
        server.createContext("/broken", exchange -> respond(exchange, 503, "down"));
        server.createContext("/html", exchange -> respond(exchange, 200, "<html>maintenance</html>"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> nobitex(boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "نوبیتکس آزمایشی");
        body.put("kind", "NOBITEX");
        body.put("url", base + "/");
        body.put("enabled", enabled);
        body.put("mappings", List.of(Map.of("commodity", "USDT", "path", "USDT"), Map.of("commodity", "USD", "path", "usdt"),
                Map.of("commodity", "BTC", "path", "btc"), Map.of("commodity", "ETH", "path", "eth"), Map.of("commodity", "TON", "path", "ton")));
        return body;
    }

    private static Map<String, Object> goldApi() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "API طلا");
        body.put("kind", "JSON");
        body.put("url", base + "/gold.json");
        body.put("unit", "RIAL");
        body.put("headers", List.of(Map.of("name", "X-Api-Key", "value", "secret")));
        body.put("mappings", List.of(Map.of("commodity", "GOLD18", "path", "/data/gold18/price"),
                Map.of("commodity", "COIN_EMAMI", "path", "/data/coin/emami"),
                Map.of("commodity", "COIN_HALF", "path", "/data/coin/half"),
                Map.of("commodity", "MESGHAL", "path", "/data/gold18/price", "multiplier", "4.3318")));
        body.put("intervalMinutes", 60);
        body.put("enabled", true);
        return body;
    }

    private long create(TestUser admin, Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return readJson(json).get("id").asLong();
    }

    private String latest(TestUser user, String code) throws Exception {
        String json = mvc.perform(getAs(user, "/api/v1/commodities")).andReturn().getResponse().getContentAsString();
        for (JsonNode c : readJson(json)) {
            if (c.get("code").asString().equals(code)) {
                JsonNode price = c.get("latestPrice");
                return price == null || price.isNull() ? null : price.get("priceToman").asString() + "@" + price.get("source").asString();
            }
        }
        throw new AssertionError("no commodity " + code);
    }

    @Test
    void nobitexPricesAreRecordedInTomanAndOnlyWhenChanged() throws Exception {
        TestUser admin = createUser("admin");
        long id = create(admin, nobitex(true));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.recorded").value(4))
                .andExpect(jsonPath("$.results[0].priceToman").value("104000"))
                .andExpect(jsonPath("$.results[2].raw").value("75000000000"))
                // a closed market without a last trade falls back to the mark price
                .andExpect(jsonPath("$.results[3].priceToman").value("288000000"))
                .andExpect(jsonPath("$.results[4].error").value("بازار ton-rls در پاسخ نوبیتکس نیست."));
        assertThat(requests.getLast()).isEqualTo("/market/stats?srcCurrency=usdt,btc,eth,ton&dstCurrency=rls");
        assertThat(latest(admin, "USDT")).isEqualTo("104000@نوبیتکس آزمایشی");
        assertThat(latest(admin, "USD")).isEqualTo("104000@نوبیتکس آزمایشی");
        assertThat(latest(admin, "BTC")).isEqualTo("7500000000@نوبیتکس آزمایشی");

        // the same prices half an hour later add nothing; a changed one is recorded
        clock.advance(Duration.ofMinutes(30));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id)).andExpect(jsonPath("$.recorded").value(0));
        usdtRial = "1052000";
        try {
            clock.advance(Duration.ofMinutes(30));
            mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id)).andExpect(jsonPath("$.recorded").value(2));
        } finally {
            usdtRial = "1040000";
        }
        assertThat(jdbc.sql("SELECT count(*) FROM prices WHERE source = 'نوبیتکس آزمایشی'").query(Long.class).single()).isEqualTo(6);

        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastCount").value(4))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastError").value("تون‌کوین: بازار ton-rls در پاسخ نوبیتکس نیست."))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].unit").value("RIAL"));
    }

    @Test
    void jsonSourcesKeepTheirHeadersSecret() throws Exception {
        TestUser admin = createUser("admin");
        long id = create(admin, goldApi());
        String list = mvc.perform(getAs(admin, "/api/v1/admin/price-sources")).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"name\":\"X-Api-Key\",\"hasValue\":true").doesNotContain("secret");
        assertThat(jdbc.sql("SELECT headers_enc FROM price_sources WHERE id = ?").param(id).query(String.class).single()).doesNotContain("secret");

        // a dry run of an edit, with the stored key
        Map<String, Object> edit = goldApi();
        edit.put("headers", List.of(new HashMap<>(Map.of("name", "X-Api-Key"))));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test?id={id}", edit, id))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.results[0].raw").value("89500000"))
                .andExpect(jsonPath("$.results[0].priceToman").value("8950000"))
                .andExpect(jsonPath("$.results[1].priceToman").value("98000000"))
                .andExpect(jsonPath("$.results[2].error").value("مقدار «n/a» عدد مثبت نیست."))
                .andExpect(jsonPath("$.results[3].priceToman").value("38769610"))
                .andExpect(jsonPath("$.recorded").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM prices").query(Long.class).single()).isZero();

        // without the key the API refuses
        Map<String, Object> keyless = goldApi();
        keyless.remove("headers");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", keyless))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۰۱ پاسخ داد."));

        // the stored key is not lent to another address
        Map<String, Object> elsewhere = goldApi();
        elsewhere.put("url", "https://collector.example/prices");
        elsewhere.put("headers", List.of(new HashMap<>(Map.of("name", "X-Api-Key"))));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test?id={id}", elsewhere, id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("priceSource.headerForNewUrl"));
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", elsewhere, id))
                .andExpect(jsonPath("$.code").value("priceSource.headerForNewUrl"));

        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", edit, id)).andExpect(status().isOk());
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id)).andExpect(jsonPath("$.recorded").value(3));
        assertThat(latest(admin, "GOLD18")).isEqualTo("8950000@API طلا");
    }

    @Test
    void failuresKeepTheLastGoodPrice() throws Exception {
        TestUser admin = createUser("admin");
        long id = create(admin, goldApi());
        feeds.run(id);
        Map<String, Object> broken = goldApi();
        broken.put("url", base + "/broken");
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", broken, id)).andExpect(status().isOk());
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۵۰۳ پاسخ داد."));
        assertThat(latest(admin, "GOLD18")).isEqualTo("8950000@API طلا");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastError").value("سرویس با کد HTTP ۵۰۳ پاسخ داد."))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastSuccessAt").value("2026-10-06T08:30:00Z"));

        Map<String, Object> html = goldApi();
        html.put("url", base + "/html");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", html)).andExpect(jsonPath("$.error").value("پاسخ سرویس JSON معتبر نیست."));
        Map<String, Object> nowhere = goldApi();
        nowhere.put("url", "http://127.0.0.1:1/prices");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", nowhere))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("اتصال به سرویس برقرار نشد")));
    }

    @Test
    void dueSourcesRunOnTheirInterval() throws Exception {
        TestUser admin = createUser("admin");
        create(admin, goldApi()); // hourly
        create(admin, nobitex(false));
        assertThat(feeds.runDue()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(59));
        assertThat(feeds.runDue()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(feeds.runDue()).isEqualTo(1);
    }

    @Test
    void oldFetchedPricesAreThinnedToOnePerDay() throws Exception {
        TestUser admin = createUser("admin");
        Instant old = Instant.parse("2026-08-20T05:00:00Z"); // 47 days before today
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            times.add(old.plus(Duration.ofHours(3L * i)));
        }
        for (Instant t : times) {
            globalPrice("USDT", String.valueOf(100000 + t.getEpochSecond() % 1000), t);
        }
        mvc.perform(postAs(admin, "/api/v1/prices", Map.of("commodity", "USDT", "priceToman", "99000", "pricedAt", "2026-08-20T06:00:00Z")))
                .andExpect(status().isCreated());
        globalPrice("USDT", "104000", Instant.parse("2026-10-05T05:00:00Z"));
        globalPrice("USDT", "104500", Instant.parse("2026-10-05T08:00:00Z"));

        assertThat(feeds.thinOldPrices()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT priced_at FROM prices WHERE source = 'TEST' ORDER BY priced_at")
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).list())
                .containsExactly(times.getLast(), Instant.parse("2026-10-05T05:00:00Z"), Instant.parse("2026-10-05T08:00:00Z"));
        assertThat(jdbc.sql("SELECT count(*) FROM prices WHERE source = 'MANUAL'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void validatesSourcesAndIsForAdminsOnly() throws Exception {
        TestUser admin = createUser("admin");
        TestUser user = createUser("sina");
        mvc.perform(getAs(user, "/api/v1/admin/price-sources")).andExpect(status().isForbidden());

        Map<String, Object> body = goldApi();
        body.put("url", "file:///etc/passwd");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidUrl"));
        body = goldApi();
        body.put("mappings", List.of(Map.of("commodity", "NOPE", "path", "/x")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.unknownCommodity"));
        body.put("mappings", List.of(Map.of("commodity", "IRT", "path", "/x")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidMappings"));
        body.put("mappings", List.of(Map.of("commodity", "USD", "path", "data.usd")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidPath"));
        body = nobitex(true);
        body.put("mappings", List.of(Map.of("commodity", "USDT", "path", "/stats/usdt")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidPath"));
        body = goldApi();
        body.put("headers", List.of(Map.of("name", "X Bad", "value", "1")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidHeaders"));
        body = goldApi();
        body.put("intervalMinutes", 1);
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidInterval"));
        create(admin, goldApi());
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", goldApi())).andExpect(status().isConflict());

        // the seeded Nobitex source is there, switched off
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[0].name").value("نوبیتکس"))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].mappings.length()").value(4));
    }
}
