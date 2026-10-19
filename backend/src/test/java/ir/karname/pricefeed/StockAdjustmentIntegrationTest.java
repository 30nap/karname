package ir.karname.pricefeed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ir.karname.account.AccountType;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.notification.NotificationService;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TSETMC as it really behaves: it refuses clients that do not look like a browser, answers 403
 * outside Iran, and after a capital increase starts the session from an adjusted previous price.
 */
class StockAdjustmentIntegrationTest extends FinanceTestSupport {

    private static final String ID = "46348559193224090";
    /** ok, or forbidden as from outside Iran. */
    private static volatile String mode = "ok";
    /** The quote served: close and previous price in Rial, and the session date. */
    private static volatile String quote = quote(26200, 25990, 20261004);
    private static final HttpServer server = startServer();

    @Autowired
    private StockService stocks;

    @Autowired
    private NotificationService notifications;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("karname.stocks.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    static String quote(long close, long previous, int day) {
        return """
                {"closingPriceInfo":{"instrumentState":{"cEtaval":"A ","cEtavalTitle":"مجاز"},"insCode":"0","dEven":%d,"hEven":123000,
                  "finalLastDate":%d,"pClosing":%d.0,"pDrCotVal":%d.0,"priceYesterday":%d.0,"qTotTran5J":3470385961.0}}"""
                .formatted(day, day, close, close, previous);
    }

    private static HttpServer startServer() {
        try {
            HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            result.createContext("/api/", exchange -> {
                String agent = exchange.getRequestHeaders().getFirst("User-Agent");
                boolean browser = agent != null && agent.startsWith("Mozilla/5.0")
                        && "https://www.tsetmc.com/".equals(exchange.getRequestHeaders().getFirst("Referer"));
                if ("forbidden".equals(mode) || !browser) {
                    respond(exchange, 403, "<html><head><title>403 Forbidden</title></head></html>");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/Instrument/GetInstrumentInfo/")) {
                    respond(exchange, 200, "{\"instrumentInfo\":{\"insCode\":\"" + ID + "\",\"lVal18AFC\":\"فولاد\",\"lVal30\":\"فولاد مبارکه اصفهان\",\"flow\":1}}");
                } else if (path.startsWith("/api/ClosingPrice/GetClosingPriceInfo/")) {
                    respond(exchange, 200, quote);
                } else {
                    respond(exchange, 200, "<html>not found</html>");
                }
            });
            result.start();
            return result;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("<") ? "text/html" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void reset() {
        mode = "ok";
        quote = quote(26200, 25990, 20261004);
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void anAdjustedPriceIsRecordedAndItsHolderIsToldToRecordBonusShares() {
        TestUser user = createUser("investor");
        // asked like a browser, or TSETMC would refuse
        CommodityView added = stocks.create(user.id(), ID);
        assertThat(added.latestPrice().priceToman()).isEqualByComparingTo("2620");
        account(user, "سهام فولاد", AccountType.INVESTMENT, added.code(), "1000");

        // the next session starts from 1,310 after a 100% capital increase
        quote = quote(12900, 13100, 20261005);
        CommodityView after = stocks.refresh(user.id(), added.code());
        assertThat(after.latestPrice().priceToman()).isEqualByComparingTo("1290");
        assertThat(after.stock().lastError()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM price_adjustments").query(Long.class).single()).isEqualTo(1);
        notifications.refresh(user.id());
        assertThat(jdbc.sql("SELECT title || ' ' || body FROM notifications WHERE type = 'PRICE_ADJUSTED'").query(String.class).single())
                .contains("قیمت فولاد تعدیل شد").contains("۲٬۶۲۰").contains("۱٬۳۱۰").contains("تطبیق موجودی");

        // an ordinary next session, whose previous price is the last close, is not an adjustment
        quote = quote(13300, 12900, 20261006);
        stocks.refresh(user.id(), added.code());
        assertThat(jdbc.sql("SELECT count(*) FROM price_adjustments").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRefusalFromOutsideIranIsExplained() {
        TestUser user = createUser("investor");
        CommodityView added = stocks.create(user.id(), ID);
        mode = "forbidden";
        quote = quote(27000, 26200, 20261005);
        CommodityView after = stocks.refresh(user.id(), added.code());
        assertThat(after.stock().lastError()).contains("فقط از اینترنت ایران");
        assertThat(after.latestPrice().priceToman()).isEqualByComparingTo("2620");
    }
}
