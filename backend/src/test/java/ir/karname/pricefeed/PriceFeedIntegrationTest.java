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
    private static volatile boolean coinsDown;

    @Autowired
    private PriceFeedService feeds;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/stats", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            // like Nobitex: one unknown symbol and the whole request is refused
            if (exchange.getRequestURI().getQuery().contains("xyz")) {
                respond(exchange, 400, "{\"status\": \"failed\", \"code\": \"InvalidSrcCurrency\", \"message\": \"Invalid srcCurrency\"}");
                return;
            }
            // and a rate limit once asked too often ("zzz" plays the request that hits it)
            if (exchange.getRequestURI().getQuery().contains("zzz")) {
                respond(exchange, 429, "{\"status\": \"failed\", \"code\": \"TooManyRequests\", \"message\": \"Too many requests\", \"backOff\": 60}");
                return;
            }
            respond(exchange, 200, """
                    {"status": "ok", "stats": {
                      "usdt-rls": {"isClosed": false, "bestSell": "1041000", "latest": "%s", "mark": "1039000"},
                      "btc-rls": {"isClosed": false, "bestSell": "75100000000", "latest": "75000000000"},
                      "eth-rls": {"isClosed": true, "bestSell": "2900000000", "mark": "2880000000"},
                      "gram-rls": {"isClosed": false, "bestSell": "52100000", "latest": "52000000"}
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
        // dollar rates from three services, one of them keyed through the address
        server.createContext("/fx-rial.json", exchange -> respond(exchange, 200, "{\"usd\": \"1,050,000\"}"));
        server.createContext("/fx-toman.json", exchange -> respond(exchange, 200, "{\"usd\": 103900}"));
        server.createContext("/fx-keyed", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || !query.equals("api_key=k3y%2B1")) {
                respond(exchange, 401, "{\"message\": \"key " + (query == null ? "" : query.replace("api_key=", "")) + " is not valid\"}");
                return;
            }
            respond(exchange, 200, "[{\"symbol\": \"EUR\", \"price\": 120000}, {\"symbol\": \"USD\", \"price\": 104500}]");
        });
        server.createContext("/html", exchange -> respond(exchange, 200, "<html>maintenance</html>"));
        // the Emami coin from a good source and from one that quotes thousands of Toman as Toman
        server.createContext("/coins-good.json", exchange -> respond(exchange, coinsDown ? 503 : 200, coinsDown ? "down" : "{\"coin\": \"209000000\"}"));
        server.createContext("/coins-wrong.json", exchange -> respond(exchange, 200, "{\"coin\": \"209000000000\"}"));
        server.createContext("/fx-error.json", exchange -> respond(exchange, 200, "{\"success\": false, \"message\": \"API key expired\"}"));
        server.createContext("/echo-auth", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 401, "{\"message\": \"Invalid token " + (auth == null ? "" : auth.replaceFirst("^Bearer ", "")) + "\"}");
        });
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

    private static Map<String, Object> dollar(String name, String path, String pointer, String unit) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("kind", "JSON");
        body.put("url", base + path);
        body.put("unit", unit);
        body.put("mappings", List.of(Map.of("commodity", "USD", "path", pointer)));
        body.put("intervalMinutes", 30);
        body.put("enabled", true);
        return body;
    }

    private static Map<String, Object> keyed(String key) {
        Map<String, Object> body = dollar("کلیددار", "/fx-keyed?api_key={key}", "/[symbol=USD]/price", "TOMAN");
        if (key != null) {
            body.put("apiKey", key);
        }
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
                // a closed market's figures are frozen: not a price
                .andExpect(jsonPath("$.results[3].error").value("بازار eth-rls در نوبیتکس بسته است؛ قیمت آن به‌روز نیست."))
                // Toncoin is Gram since June 2026: "ton" is asked as "gram"
                .andExpect(jsonPath("$.results[4].priceToman").value("5200000"));
        assertThat(requests.getLast()).isEqualTo("/market/stats?srcCurrency=usdt,btc,eth,gram&dstCurrency=rls");
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
        assertThat(latest(admin, "TON")).isEqualTo("5200000@نوبیتکس آزمایشی");

        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastCount").value(4))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastError").value("اتریوم: بازار eth-rls در نوبیتکس بسته است؛ قیمت آن به‌روز نیست."))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].unit").value("RIAL"));
    }

    @Test
    void anUnknownNobitexSymbolDoesNotStopTheOthers() throws Exception {
        TestUser admin = createUser("admin");
        Map<String, Object> body = nobitex(true);
        body.put("mappings", List.of(Map.of("commodity", "USDT", "path", "usdt"), Map.of("commodity", "TON", "path", "xyz")));
        long id = create(admin, body);
        requests.clear();
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.recorded").value(1))
                .andExpect(jsonPath("$.results[0].priceToman").value("104000"))
                .andExpect(jsonPath("$.results[1].error").value(
                        "نوبیتکس نماد xyz را نپذیرفت (شاید نامش عوض شده یا حذف شده است): سرویس با کد HTTP ۴۰۰ پاسخ داد: Invalid srcCurrency"));
        assertThat(requests).containsExactly("/market/stats?srcCurrency=usdt,xyz&dstCurrency=rls",
                "/market/stats?srcCurrency=usdt&dstCurrency=rls", "/market/stats?srcCurrency=xyz&dstCurrency=rls");
        assertThat(latest(admin, "USDT")).isEqualTo("104000@نوبیتکس آزمایشی");

        // when every symbol is refused, the source's own error is the answer
        body.put("mappings", List.of(Map.of("commodity", "TON", "path", "xyz")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", body))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۰۰ پاسخ داد: Invalid srcCurrency"));
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
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۰۱ پاسخ داد: unauthorized"));

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
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۵۰۳ پاسخ داد: down"));
        assertThat(latest(admin, "GOLD18")).isEqualTo("8950000@API طلا");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].lastError").value("سرویس با کد HTTP ۵۰۳ پاسخ داد: down"))
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
    void thePreferredSourceIsRecordedAndAnotherTakesOverWhenItGoesQuiet() throws Exception {
        TestUser admin = createUser("admin");
        long rial = create(admin, dollar("منبع اول", "/fx-rial.json", "/usd", "RIAL"));
        long keyed = create(admin, keyed("k3y+1"));

        // only the second has a quote yet: it is used
        feeds.run(keyed);
        assertThat(latest(admin, "USD")).isEqualTo("104500@کلیددار");
        // the preferred one arrives and takes over
        clock.advance(Duration.ofMinutes(5));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), rial)).andExpect(jsonPath("$.recorded").value(1));
        assertThat(latest(admin, "USD")).isEqualTo("105000@منبع اول");
        // while its quote is fresh, the backup only backs it up
        clock.advance(Duration.ofMinutes(30));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), keyed)).andExpect(jsonPath("$.recorded").value(0));
        assertThat(latest(admin, "USD")).isEqualTo("105000@منبع اول");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].source").value("منبع اول"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[0].status").value("CHOSEN"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[1].status").value("BACKUP"));

        // the preferred source falls silent past twice its interval and five minutes: the backup's next quote is recorded
        clock.advance(Duration.ofMinutes(36));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), keyed)).andExpect(jsonPath("$.recorded").value(1));
        assertThat(latest(admin, "USD")).isEqualTo("104500@کلیددار");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[0].status").value("STALE"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[1].status").value("CHOSEN"));

        // back again, and back in use; then the admin prefers the other one
        clock.advance(Duration.ofMinutes(1));
        feeds.run(rial);
        assertThat(latest(admin, "USD")).isEqualTo("105000@منبع اول");
        long nobitexSeed = jdbc.sql("SELECT id FROM price_sources WHERE name = 'نوبیتکس'").query(Long.class).single();
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/order", Map.of("ids", List.of(keyed, rial, nobitexSeed))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(keyed))
                .andExpect(jsonPath("$[0].priority").value(10));
        clock.advance(Duration.ofMinutes(1));
        feeds.run(keyed);
        assertThat(latest(admin, "USD")).isEqualTo("104500@کلیددار");
        assertThat(jdbc.sql("SELECT count(*) FROM prices WHERE commodity_id = (SELECT id FROM commodities WHERE code = 'USD')")
                .query(Long.class).single()).isEqualTo(5);

        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/order", Map.of("ids", List.of(keyed, rial))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("priceSource.invalidOrder"));
    }

    @Test
    void aSwitchedOffSourceRunByHandIsUsedThatOnce() throws Exception {
        TestUser admin = createUser("admin");
        Map<String, Object> body = dollar("خاموش", "/fx-toman.json", "/usd", "TOMAN");
        body.put("enabled", false);
        long off = create(admin, body);
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), off)).andExpect(jsonPath("$.recorded").value(1));
        assertThat(latest(admin, "USD")).isEqualTo("103900@خاموش");
        // but it is not a candidate when others run
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[0].status").value("OFF"));
        assertThat(feeds.runDue()).isZero();
    }

    @Test
    void aSourceFarFromTheOthersIsSetAside() throws Exception {
        TestUser admin = createUser("admin");
        // Rial figures declared as Toman: ten times too much, and preferred
        long wrong = create(admin, dollar("واحد اشتباه", "/fx-rial.json", "/usd", "TOMAN"));
        long rial = create(admin, dollar("ریالی", "/fx-rial.json", "/usd", "RIAL"));
        long toman = create(admin, dollar("تومانی", "/fx-toman.json", "/usd", "TOMAN"));
        feeds.run(rial);
        feeds.run(toman);
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), wrong)).andExpect(jsonPath("$.recorded").value(0));
        assertThat(latest(admin, "USD")).isEqualTo("105000@ریالی");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + wrong + ")].lastError").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("«واحد اشتباه» بیش از ۲۰٪ با منابع دیگر فرق داشت"))));
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes[0].status").value("OUTLIER"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].warning").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("کنار گذاشته شد"))));
    }

    @Test
    void anApiKeyInTheAddressIsStoredApartAndNeverShown() throws Exception {
        TestUser admin = createUser("admin");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", keyed(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("priceSource.keyRequired"));
        long id = create(admin, keyed("k3y+1"));
        String list = mvc.perform(getAs(admin, "/api/v1/admin/price-sources")).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("k3y").contains("{key}");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].usesKey").value(true))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].hasApiKey").value(true));
        assertThat(jdbc.sql("SELECT api_key_enc FROM price_sources WHERE id = ?").param(id).query(String.class).single()).doesNotContain("k3y");

        // a dry run of an edit borrows the stored key; a wrong key is refused and not echoed back
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test?id={id}", keyed(null), id))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.results[0].priceToman").value("104500"));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", keyed("wrong-key")))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۰۱ پاسخ داد: key ••• is not valid"));

        // the stored key does not follow the source to another address
        Map<String, Object> moved = keyed(null);
        moved.put("url", "https://collector.example/fx?k={key}");
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", moved, id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("priceSource.keyForNewUrl"));
        moved.put("apiKey", "another");
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", moved, id)).andExpect(status().isOk());
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
                .andExpect(jsonPath("$[0].mappings.length()").value(4))
                .andExpect(jsonPath("$[0].mappings[3].path").value("gram"));
    }

    private static Map<String, Object> coin(String name, String path) {
        Map<String, Object> body = dollar(name, path, "/coin", "TOMAN");
        body.put("mappings", List.of(Map.of("commodity", "COIN_EMAMI", "path", "/coin")));
        return body;
    }

    private String lastError(TestUser admin, long id) throws Exception {
        String json = mvc.perform(getAs(admin, "/api/v1/admin/price-sources")).andReturn().getResponse().getContentAsString();
        for (JsonNode source : readJson(json)) {
            if (source.get("id").asLong() == id) {
                return source.get("lastError").isNull() ? null : source.get("lastError").asString();
            }
        }
        throw new AssertionError("no source " + id);
    }

    @Test
    void aWrongBackupCannotReplaceAGoodPriceWhileThePreferredSourceIsDown() throws Exception {
        TestUser admin = createUser("admin");
        long good = create(admin, coin("خوب", "/coins-good.json"));
        long wrong = create(admin, coin("هزار برابر", "/coins-wrong.json"));
        assertThat(feeds.runDue()).isEqualTo(2);
        assertThat(latest(admin, "COIN_EMAMI")).isEqualTo("209000000@خوب");
        assertThat(lastError(admin, wrong)).contains("اختلاف");

        // the good source is down past its freshness; the scheduled runs leave the wrong price out
        coinsDown = true;
        try {
            clock.advance(Duration.ofMinutes(66));
            assertThat(feeds.runDue()).isEqualTo(2);
        } finally {
            coinsDown = false;
        }
        assertThat(latest(admin, "COIN_EMAMI")).isEqualTo("209000000@خوب");
        assertThat(lastError(admin, wrong)).contains("ثبت نشد");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'COIN_EMAMI')].quotes[0].status").value("STALE"))
                .andExpect(jsonPath("$[?(@.commodity == 'COIN_EMAMI')].quotes[1].status").value("HELD"))
                .andExpect(jsonPath("$[?(@.commodity == 'COIN_EMAMI')].recordedSource").value("خوب"));

        // back again, the good source is in use, as the reference was never the wrong price
        clock.advance(Duration.ofMinutes(31));
        feeds.runDue();
        assertThat(latest(admin, "COIN_EMAMI")).isEqualTo("209000000@خوب");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'COIN_EMAMI')].quotes[0].status").value("CHOSEN"));

        // while the good source is fresh, fetching the other by hand changes nothing
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), wrong)).andExpect(jsonPath("$.recorded").value(0));
        // with nothing to confirm or contradict it, the admin fetching it by hand confirms the jump
        coinsDown = true;
        try {
            clock.advance(Duration.ofMinutes(66));
            feeds.runDue();
            mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), wrong)).andExpect(jsonPath("$.recorded").value(1));
        } finally {
            coinsDown = false;
        }
        assertThat(latest(admin, "COIN_EMAMI")).isEqualTo("209000000000@هزار برابر");
    }

    @Test
    void aSourceThatTakesOverWithAnEarlierQuoteIsRecordedAtOnce() throws Exception {
        TestUser admin = createUser("admin");
        long rial = create(admin, dollar("ریالی", "/fx-rial.json", "/usd", "RIAL"));
        long toman = create(admin, dollar("تومانی", "/fx-toman.json", "/usd", "TOMAN"));
        feeds.run(toman);
        clock.advance(Duration.ofMinutes(5));
        feeds.run(rial);
        assertThat(latest(admin, "USD")).isEqualTo("105000@ریالی");
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/order", Map.of("ids", List.of(toman, rial,
                jdbc.sql("SELECT id FROM price_sources WHERE name = 'نوبیتکس'").query(Long.class).single())))).andExpect(status().isOk());
        // the other source runs next: the newly preferred quote, fetched before the last recorded price, is recorded now
        clock.advance(Duration.ofMinutes(5));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), rial)).andExpect(jsonPath("$.recorded").value(1));
        assertThat(latest(admin, "USD")).isEqualTo("103900@تومانی");
        assertThat(jdbc.sql("SELECT max(priced_at) FROM prices WHERE source = 'تومانی'").query(java.sql.Timestamp.class).single().toInstant())
                .isEqualTo(clock.instant());
    }

    @Test
    void anEditedSourceNoLongerStandsForWhatItStoppedPublishing() throws Exception {
        TestUser admin = createUser("admin");
        long keyed = create(admin, keyed("k3y+1"));
        long toman = create(admin, dollar("تومانی", "/fx-toman.json", "/usd", "TOMAN"));
        feeds.run(keyed);
        feeds.run(toman);
        assertThat(latest(admin, "USD")).isEqualTo("104500@کلیددار");
        // the preferred source now publishes the euro only: its dollar quote goes, and the other takes over at once
        Map<String, Object> euro = keyed(null);
        euro.put("mappings", List.of(Map.of("commodity", "EUR", "path", "/[symbol=EUR]/price")));
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", euro, keyed)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        feeds.run(toman);
        assertThat(latest(admin, "USD")).isEqualTo("103900@تومانی");
        mvc.perform(getAs(admin, "/api/v1/admin/price-sources/coverage"))
                .andExpect(jsonPath("$[?(@.commodity == 'USD')].quotes.length()").value(1));
        // a longer interval does not make an old quote fresh again
        assertThat(jdbc.sql("SELECT count(*) FROM price_quotes WHERE source_id = ? AND fresh_until > fetched_at").param(toman)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void errorsNeverCarryTheCredentialAndOddAnswersAreExplained() throws Exception {
        TestUser admin = createUser("admin");
        // the service echoes the token it was given
        Map<String, Object> echo = dollar("پژواک", "/echo-auth", "/usd", "TOMAN");
        echo.put("headers", List.of(Map.of("name", "Authorization", "value", "Bearer abc123token")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", echo))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۰۱ پاسخ داد: Invalid token •••"));

        // an error answered with 200: the service's own reason, not a list of missing paths
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", dollar("خطادار", "/fx-error.json", "/usd", "TOMAN")))
                .andExpect(jsonPath("$.error").value("هیچ‌کدام از مسیرها در پاسخ نبود؛ شاید سرویس خطا داده یا قالب پاسخ عوض شده است: API key expired"));

        // a price that cannot be recorded is an error of its mapping, not of the whole run
        Map<String, Object> huge = dollar("بزرگ", "/fx-rial.json", "/usd", "TOMAN");
        huge.put("mappings", List.of(Map.of("commodity", "USD", "path", "/usd", "multiplier", "1000000000")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", huge))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.results[0].error").value(org.hamcrest.Matchers.containsString("پذیرفتنی نیست")));

        // a key copied with invisible marks and Persian digits is cleaned
        long id = create(admin, keyed("\u200Ek۳y+1\u200F "));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/{id}/run", Map.of(), id)).andExpect(jsonPath("$.recorded").value(1));
    }

    @Test
    void nobitexKeepsWhatArrivedWhenARateLimitStopsTheRetries() throws Exception {
        TestUser admin = createUser("admin");
        Map<String, Object> body = nobitex(true);
        body.put("mappings", List.of(Map.of("commodity", "USDT", "path", "usdt"), Map.of("commodity", "TON", "path", "xyz"),
                Map.of("commodity", "BTC", "path", "zzz"), Map.of("commodity", "ETH", "path", "eth")));
        requests.clear();
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", body))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.results[0].priceToman").value("104000"))
                .andExpect(jsonPath("$.results[1].error").value(org.hamcrest.Matchers.startsWith("نوبیتکس نماد xyz را نپذیرفت")))
                .andExpect(jsonPath("$.results[2].error").value("سرویس با کد HTTP ۴۲۹ پاسخ داد: Too many requests"))
                .andExpect(jsonPath("$.results[3].error").value("سرویس با کد HTTP ۴۲۹ پاسخ داد: Too many requests"));
        // nothing asked after the limit
        assertThat(requests).hasSize(4);

        // the limit before anything arrived is the source's error
        body.put("mappings", List.of(Map.of("commodity", "BTC", "path", "zzz"), Map.of("commodity", "TON", "path", "xyz")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test", body))
                .andExpect(jsonPath("$.error").value("سرویس با کد HTTP ۴۲۹ پاسخ داد: Too many requests"));
    }

    @Test
    void addressesKeysAndHeadersThatCouldMisdirectARequestAreRefused() throws Exception {
        TestUser admin = createUser("admin");
        // the key may not pick the host
        Map<String, Object> body = keyed("254");
        body.put("url", "http://169.254.169.{key}/latest/meta-data/");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidUrl"));
        body.put("url", "http://{key}/computeMetadata/v1/");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidUrl"));
        // headers the HTTP client sets itself, and values it cannot send
        body = goldApi();
        body.put("headers", List.of(Map.of("name", "Host", "value", "example.com")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidHeaders"));
        body.put("headers", List.of(Map.of("name", "X-Api-Key", "value", "کلید")));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidHeaders"));
        // names that would pass for hand-entered prices
        body = goldApi();
        body.put("name", "manual");
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources", body)).andExpect(jsonPath("$.code").value("priceSource.invalidName"));

        // Nobitex without an address reads its default one: its stored secrets do not follow it elsewhere
        Map<String, Object> nobitex = nobitex(true);
        nobitex.remove("url");
        nobitex.put("headers", List.of(Map.of("name", "Authorization", "value", "Token {key}")));
        nobitex.put("apiKey", "s3cr3t-token");
        long id = create(admin, nobitex);
        Map<String, Object> elsewhere = dollar("نوبیتکس", "/fx-toman.json", "/usd", "TOMAN");
        elsewhere.put("url", "https://collector.example/x");
        elsewhere.put("headers", List.of(new HashMap<>(Map.of("name", "Authorization"))));
        mvc.perform(postAs(admin, "/api/v1/admin/price-sources/test?id={id}", elsewhere, id))
                .andExpect(jsonPath("$.code").value("priceSource.headerForNewUrl"));
        // the default address written out is the same address
        Map<String, Object> explicit = nobitex(true);
        explicit.put("url", "https://apiv2.nobitex.ir/");
        explicit.put("headers", List.of(new HashMap<>(Map.of("name", "Authorization"))));
        mvc.perform(putAs(admin, "/api/v1/admin/price-sources/{id}", explicit, id)).andExpect(status().isOk());
    }
}
