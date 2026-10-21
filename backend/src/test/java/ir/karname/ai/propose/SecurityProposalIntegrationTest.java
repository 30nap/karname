package ir.karname.ai.propose;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ir.karname.account.Account;
import ir.karname.account.BalanceService;
import ir.karname.ai.llm.Part;
import ir.karname.ai.tools.FinanceTools;
import ir.karname.ai.tools.FinanceTools.Outcome;
import ir.karname.ai.tools.FinanceTools.ToolContext;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Shares proposed in conversation: looked up on the exchange (a stand-in for TSETMC here), followed only on confirmation. */
class SecurityProposalIntegrationTest extends FinanceTestSupport {

    private static final String FOLAD = "46348559193224090";
    private static final HttpServer server = startServer();

    @Autowired
    private FinanceTools tools;

    @Autowired
    private ProposalService proposals;

    @Autowired
    private BalanceService balances;

    private TestUser user;
    private Account bank;
    private ToolContext ctx;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("karname.stocks.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    private static HttpServer startServer() {
        try {
            HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            result.createContext("/api/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/Instrument/GetInstrumentSearch/")) {
                    String q = path.substring("/api/Instrument/GetInstrumentSearch/".length());
                    respond(exchange, q.contains("فولاد") ? """
                            {"instrumentSearch":[
                              {"insCode":"%s","lVal18AFC":"فولاد","lVal30":"فولاد مبارکه اصفهان","flow":1,"lastDate":1},
                              {"insCode":"778253364357513","lVal18AFC":"فولادح","lVal30":"ح . فولاد مبارکه اصفهان","flow":1,"lastDate":1}]}"""
                            .formatted(FOLAD) : q.contains("خودرو") ? """
                            {"instrumentSearch":[
                              {"insCode":"111","lVal18AFC":"خودرو","lVal30":"ایران خودرو","flow":1,"lastDate":1},
                              {"insCode":"222","lVal18AFC":"خودروح","lVal30":"ح . ایران خودرو","flow":1,"lastDate":1},
                              {"insCode":"333","lVal18AFC":"خودرو۲","lVal30":"ایران خودرو دیزل","flow":2,"lastDate":1}]}"""
                            : "{\"instrumentSearch\":[]}");
                } else if (path.startsWith("/api/Instrument/GetInstrumentInfo/")) {
                    respond(exchange, "{\"instrumentInfo\":{\"insCode\":\"" + FOLAD + "\",\"lVal18AFC\":\"فولاد\",\"lVal30\":\"فولاد مبارکه اصفهان\",\"flow\":1}}");
                } else if (path.startsWith("/api/ClosingPrice/GetClosingPriceInfo/")) {
                    respond(exchange, """
                            {"closingPriceInfo":{"insCode":"0","dEven":20261005,"hEven":123000,"pClosing":26200.0,"pDrCotVal":26200.0,
                              "priceYesterday":25990.0,"qTotTran5J":3470385961.0}}""");
                } else {
                    respond(exchange, "<html>not found</html>");
                }
            });
            result.start();
            return result;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("<") ? "text/html" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        user = createUser("investor");
        bank = bank(user, "ملت", "50000000");
        long conversation = jdbc.sql("INSERT INTO ai_conversations (user_id, title, provider_kind, model) VALUES (:u, 'گفتگو', 'FAKE', 'm') "
                + "RETURNING id").param("u", user.id()).query(Long.class).single();
        ctx = new ToolContext(user.id(), TODAY, ZoneId.of("Asia/Tehran"), true, null, conversation, 1, FinanceTools.CURRENT_VERSION);
    }

    private Outcome call(String input) {
        return tools.run(ctx, new Part.ToolCall("call_1", "propose_security", input));
    }

    private ProposalView propose(String input) {
        Outcome outcome = call(input);
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        String ref = readJson(outcome.data()).get("proposals").get(0).asString();
        return proposals.views(user.id(), List.of(ref)).get(ref);
    }

    private static String line(ProposalView view, String label) {
        return view.lines().stream().filter(l -> l.label().equals(label)).map(Card.Line::value).findFirst()
                .orElseThrow(() -> new AssertionError("No line «" + label + "» in " + view.lines()));
    }

    private long holdings() {
        return jdbc.sql("SELECT count(*) FROM commodities WHERE user_id = :u AND stock_instrument_id = :i").param("u", user.id())
                .param("i", FOLAD).query(Long.class).single();
    }

    @Test
    void sharesTheUserHoldsAreFollowedAndKeptInTheirOwnAccountOnlyOnConfirmation() {
        ProposalView card = propose("{\"symbol\":\"فولاد\",\"mode\":\"HOLDING\",\"quantity\":1,\"quantity_scale\":\"THOUSAND\"}");
        assertThat(card.title()).isEqualTo("سهام «فولاد»");
        assertThat(line(card, "نام")).isEqualTo("فولاد مبارکه اصفهان");
        assertThat(line(card, "تعداد")).isEqualTo("1000");
        assertThat(line(card, "حساب نگهداری")).isEqualTo("حساب تازه‌ی «سهام فولاد»");
        assertThat(card.effects()).anyMatch(e -> e.contains("TSETMC"));
        assertThat(card.warnings()).anyMatch(w -> w.contains("بهای خرید"));
        // the check made a stand-in for the symbol and rolled it back
        assertThat(holdings()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM commodities WHERE user_id = :u").param("u", user.id()).query(Long.class).single()).isZero();

        ProposalView applied = proposals.apply(user.id(), card.ref());
        long account = Long.parseLong(applied.link().substring("/accounts/".length()));
        assertThat(holdings()).isEqualTo(1);
        assertThat(balances.balance(user.id(), account, TODAY)).isEqualByComparingTo("1000");
        assertThat(jdbc.sql("SELECT price_toman FROM prices WHERE user_id = :u AND source = 'TSETMC'").param("u", user.id())
                .query(BigDecimal.class).single()).isEqualByComparingTo("2620");

        // held already: a changed count belongs to the account's reconcile, not a second account
        Outcome again = call("{\"symbol\":\"فولاد\",\"mode\":\"HOLDING\",\"quantity\":1500}");
        assertThat(again.error()).isTrue();
        assertThat(again.content()).contains("already keeps");

        // a purchase goes into the same account, its fee in the cost; the stock exchange is not asked again
        ProposalView buy = propose("""
                {"symbol":"فولاد","mode":"BUY","quantity":500,"price_per_share":2600,"fee":15,"fee_scale":"THOUSAND"}""");
        assertThat(line(buy, "مبلغ کل")).isEqualTo("1300000");
        assertThat(line(buy, "کارمزد")).isEqualTo("15000");
        assertThat(line(buy, "حساب نگهداری")).isEqualTo("«سهام فولاد»");
        assertThat(buy.warnings()).anyMatch(w -> w.contains("«ملت» در نظر گرفته شد")).noneMatch(w -> w.contains("۵۰٪"));
        proposals.apply(user.id(), buy.ref());
        assertThat(balances.balance(user.id(), account, TODAY)).isEqualByComparingTo("1500");
        assertThat(balances.balance(user.id(), bank.getId(), TODAY)).isEqualByComparingTo("48685000");

        // ten times the market price: likely Rial given as Toman
        ProposalView rial = propose("{\"symbol\":\"فولاد\",\"mode\":\"BUY\",\"quantity\":10,\"price_per_share\":26200}");
        assertThat(rial.warnings()).anyMatch(w -> w.contains("بیش از ۵۰٪"));
    }

    @Test
    void anUnclearSymbolComesBackWithTheCandidates() {
        Outcome outcome = call("{\"symbol\":\"ایران خودرو\",\"mode\":\"HOLDING\",\"quantity\":10}");
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("several symbols").contains("«خودرو» (ایران خودرو)").contains("«خودروح»");
        Outcome none = call("{\"symbol\":\"ناموجود\",\"mode\":\"HOLDING\",\"quantity\":10}");
        assertThat(none.content()).contains("no symbol matching");
        Outcome buy = call("{\"symbol\":\"فولاد\",\"mode\":\"BUY\",\"quantity\":10}");
        assertThat(buy.content()).contains("price per share or the total");
    }
}
