package ir.karname.pricefeed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP parsing, database persistence, ownership and scheduling against a local TSETMC fixture. */
class StockIntegrationTest extends FinanceTestSupport {

    private static final String ID = "46348559193224090";
    private static final AtomicInteger quotes = new AtomicInteger();
    private static volatile boolean unavailable;
    private static volatile CountDownLatch quoteStarted;
    private static volatile CountDownLatch quoteRelease;
    private static final HttpServer server = startServer();

    @Autowired
    private StockService stocks;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("karname.stocks.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    private static HttpServer startServer() {
        try {
            HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            result.createContext("/api/Instrument/GetInstrumentSearch/", exchange -> respond(exchange, 200,
                    "{\"instrumentSearch\":[" + instrument() + "]}"));
            result.createContext("/api/Instrument/GetInstrumentInfo/", exchange -> respond(exchange, 200,
                    "{\"instrumentInfo\":" + instrument() + "}"));
            result.createContext("/api/ClosingPrice/GetClosingPriceInfo/", exchange -> {
                quotes.incrementAndGet();
                CountDownLatch started = quoteStarted;
                CountDownLatch release = quoteRelease;
                if (started != null && release != null) {
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test did not release quote");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
                respond(exchange, unavailable ? 503 : 200, unavailable ? "{\"message\":\"maintenance\"}" : """
                        {"closingPriceInfo":{"insCode":"0","dEven":20261005,"hEven":123000,
                          "pClosing":35358,"pDrCotVal":35540,"qTotTran5J":100}}
                        """);
            });
            result.start();
            return result;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String instrument() {
        return "{\"insCode\":\"" + ID + "\",\"lVal18AFC\":\"فولاد\",\"lVal30\":\"فولاد مبارکه اصفهان\",\"flow\":1,\"lastDate\":1}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void resetRemote() {
        unavailable = false;
        quoteStarted = null;
        quoteRelease = null;
        quotes.set(0);
    }

    @AfterAll
    static void stopRemote() {
        server.stop(0);
    }

    private JsonNode add(TestUser user) throws Exception {
        return readJson(mvc.perform(postAs(user, "/api/v1/securities", Map.of("instrumentId", ID)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void ordinaryUserSearchesAddsAndValuesASecurityWithoutASecondAssetOnRetry() throws Exception {
        createUser("admin");
        TestUser user = createUser("investor");
        mvc.perform(getAs(user, "/api/v1/securities/search?q=فولاد"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].instrumentId").value(ID));
        JsonNode added = add(user);
        String code = added.get("code").asString();
        assertThat(added.get("stock").get("symbol").asString()).isEqualTo("فولاد");
        assertThat(added.get("latestPrice").get("priceToman").asString()).isEqualTo("3535.8");
        assertThat(added.get("latestPrice").get("pricedAt").asString()).isEqualTo("2026-10-05T09:00:00Z");
        assertThat(add(user).get("code").asString()).isEqualTo(code);
        assertThat(quotes.get()).isEqualTo(1);
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "سهام فولاد", "type", "INVESTMENT", "commodity", code,
                        "openingBalance", "100")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.valueToman").value("353580"));
    }

    @Test
    void otherUsersCannotReadRefreshOrUnlinkTheOwnersStockAndRequestsNeedAuthentication() throws Exception {
        TestUser owner = createUser("owner");
        TestUser other = createUser("other");
        String code = add(owner).get("code").asString();
        mvc.perform(get("/api/v1/securities/search?q=فولاد")).andExpect(status().isUnauthorized());
        mvc.perform(getAs(other, "/api/v1/prices?commodity={code}", code)).andExpect(status().isNotFound());
        mvc.perform(postAs(other, "/api/v1/commodities/{code}/security/refresh", Map.of(), code)).andExpect(status().isNotFound());
        mvc.perform(putAs(other, "/api/v1/commodities/{code}/security", Map.of("instrumentId", ID), code)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(other, "/api/v1/commodities/{code}/security", code)).andExpect(status().isNotFound());
        assertThat(quotes.get()).isEqualTo(1);
    }

    @Test
    void aFailedInitialQuoteKeepsTheAssetAndALaterFailureKeepsItsLastGoodPrice() throws Exception {
        TestUser owner = createUser("owner");
        unavailable = true;
        JsonNode created = add(owner);
        String code = created.get("code").asString();
        assertThat(created.get("latestPrice").isNull()).isTrue();
        assertThat(created.get("stock").get("lastError").asString()).contains("maintenance");
        unavailable = false;
        mvc.perform(postAs(owner, "/api/v1/commodities/{code}/security/refresh", Map.of(), code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestPrice.priceToman").value("3535.8"))
                .andExpect(jsonPath("$.stock.lastError").isEmpty());
        unavailable = true;
        mvc.perform(postAs(owner, "/api/v1/commodities/{code}/security/refresh", Map.of(), code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestPrice.priceToman").value("3535.8"))
                .andExpect(jsonPath("$.stock.lastError").value(org.hamcrest.Matchers.containsString("maintenance")));
        mvc.perform(getAs(owner, "/api/v1/prices?commodity={code}", code)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void schedulerFetchesSharedSymbolsOncePerIntervalAndStopsAfterUnlink() throws Exception {
        TestUser one = createUser("one");
        TestUser two = createUser("two");
        String first = add(one).get("code").asString();
        String second = add(two).get("code").asString();
        quotes.set(0);
        stocks.runDue();
        assertThat(quotes.get()).isZero();
        clock.advance(Duration.ofMinutes(30));
        stocks.runDue();
        assertThat(quotes.get()).isEqualTo(1);
        stocks.runDue();
        assertThat(quotes.get()).isEqualTo(1);
        mvc.perform(deleteAs(one, "/api/v1/commodities/{code}/security", first)).andExpect(status().isOk());
        mvc.perform(deleteAs(two, "/api/v1/commodities/{code}/security", second)).andExpect(status().isOk());
        clock.advance(Duration.ofHours(1));
        stocks.runDue();
        assertThat(quotes.get()).isEqualTo(1);
    }

    @Test
    void aQuoteInFlightCannotReattachAnUnlinkedAsset() throws Exception {
        TestUser owner = createUser("owner");
        String code = add(owner).get("code").asString();
        quoteStarted = new CountDownLatch(1);
        quoteRelease = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh = executor.submit(() -> stocks.refresh(owner.id(), code));
            try {
                assertThat(quoteStarted.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(deleteAs(owner, "/api/v1/commodities/{code}/security", code)).andExpect(status().isOk());
            } finally {
                quoteRelease.countDown();
            }
            assertThat(refresh.get(5, TimeUnit.SECONDS).stock()).isNull();
        }
        mvc.perform(getAs(owner, "/api/v1/prices?commodity={code}", code)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void linkingKeepsExistingBalancesAndRejectsNonSecuritiesOrDuplicateBindings() throws Exception {
        TestUser user = createUser("owner");
        String code = readJson(mvc.perform(postAs(user, "/api/v1/commodities", Map.of("nameFa", "سبد قبلی", "unitFa", "سهم",
                        "kind", "SECURITY", "scale", 0))).andReturn().getResponse().getContentAsString()).get("code").asString();
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "سبد قبلی", "type", "INVESTMENT", "commodity", code,
                        "openingBalance", "10"))).andExpect(status().isCreated());
        mvc.perform(putAs(user, "/api/v1/commodities/{code}/security", Map.of("instrumentId", ID), code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameFa").value("سبد قبلی"));
        mvc.perform(getAs(user, "/api/v1/accounts"))
                .andExpect(jsonPath("$[0].balance").value("10")).andExpect(jsonPath("$[0].valueToman").value("35358"));
        mvc.perform(putAs(user, "/api/v1/commodities/USD/security", Map.of("instrumentId", ID))).andExpect(status().isForbidden());
        mvc.perform(postAs(user, "/api/v1/securities", Map.of("instrumentId", "../bad"))).andExpect(status().isBadRequest());
        assertThat(add(user).get("code").asString()).isEqualTo(code);
    }
}
