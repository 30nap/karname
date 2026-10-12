package ir.karname.pricefeed;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceJsonTest {

    private static final JsonNode ROOT = PriceJson.parse("""
            {"current": {"price_dollar_rl": {"p": "1,025,000"}},
             "gold": [{"symbol": "IR_COIN_1G", "price": 1}, {"symbol": "IR_GOLD_18K", "price": 8950000}],
             "list": [[1, 2], [3, 4]],
             "a/b": {"m~n": "ok"}}
            """);

    @Test
    void readsPlainPointers() {
        assertThat(PriceJson.at(ROOT, "/current/price_dollar_rl/p").asString()).isEqualTo("1,025,000");
        assertThat(PriceJson.at(ROOT, "/list/1/0").asInt()).isEqualTo(3);
        assertThat(PriceJson.at(ROOT, "/a~1b/m~0n").asString()).isEqualTo("ok");
    }

    @Test
    void picksAListElementByAField() {
        assertThat(PriceJson.at(ROOT, "/gold/[symbol=IR_GOLD_18K]/price").asLong()).isEqualTo(8950000);
        assertThat(PriceJson.at(ROOT, "/gold/[symbol=ir_gold_18k]/price").asLong()).isEqualTo(8950000);
        assertThat(PriceJson.at(ROOT, "/gold/[symbol=IR_GOLD_24K]/price").isMissingNode()).isTrue();
    }

    @Test
    void missingOrMismatchedPathsAreMissing() {
        assertThat(PriceJson.at(ROOT, "/current/nope/p").isMissingNode()).isTrue();
        assertThat(PriceJson.at(ROOT, "/gold/x/price").isMissingNode()).isTrue();
        assertThat(PriceJson.at(ROOT, "/current/price_dollar_rl/p/deeper").isMissingNode()).isTrue();
        assertThat(PriceJson.at(ROOT, "current").isMissingNode()).isTrue();
    }

    @Test
    void errorDetailsGiveTheServicesReasonWithoutSecrets() {
        assertThat(PriceHttpClient.detail("{\"status\":\"failed\",\"code\":\"InvalidSymbol\",\"message\":\"Invalid srcCurrency\"}", List.of()))
                .isEqualTo("Invalid srcCurrency");
        assertThat(PriceHttpClient.detail("{\"error\":{\"message\":\"bad key abcd1234\"}}", List.of("abcd1234"))).isEqualTo("bad key •••");
        assertThat(PriceHttpClient.detail("<html><head><title>403 Forbidden</title></head><body>nginx</body></html>", List.of()))
                .isEqualTo("403 Forbidden");
        assertThat(PriceHttpClient.detail("", List.of())).isEmpty();
        assertThat(PriceHttpClient.detail("x".repeat(500), List.of())).hasSize(161).endsWith("…");
        // a key that was URL-encoded into the address is hidden too
        assertThat(PriceHttpClient.redact("GET /latest?api_key=a%2Bb%3D", List.of("a+b="))).isEqualTo("GET /latest?api_key=•••");
    }
}
