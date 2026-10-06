package ir.karname.pricefeed;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Nobitex market statistics: {@code GET /market/stats?srcCurrency=btc,usdt&dstCurrency=rls},
 * public and keyless. Prices are in Rial; the last trade is used, then the mark price, then
 * the best ask. A USDT mapping onto USD gives a usable free-market dollar rate.
 */
@Component
class NobitexPriceProvider implements PriceProvider {

    static final String DEFAULT_URL = "https://apiv2.nobitex.ir";

    private final PriceHttpClient http;

    NobitexPriceProvider(PriceHttpClient http) {
        this.http = http;
    }

    @Override
    public PriceSourceKind kind() {
        return PriceSourceKind.NOBITEX;
    }

    @Override
    public List<QuoteResult> fetch(SourceConfig config) {
        Set<String> symbols = new LinkedHashSet<>();
        config.mappings().forEach(m -> symbols.add(m.path()));
        String base = config.url() == null || config.url().isBlank() ? DEFAULT_URL : config.url().replaceAll("/+$", "");
        URI uri = URI.create(base + "/market/stats?srcCurrency=" + String.join(",", symbols) + "&dstCurrency=rls");
        JsonNode root = PriceJson.parse(http.get(uri, config.headers()));
        if (!"ok".equals(root.path("status").asString(""))) {
            String message = root.path("message").asString("");
            throw new PriceFetchException("نوبیتکس خطا برگرداند" + (message.isBlank() ? "." : ": " + message));
        }
        List<QuoteResult> results = new ArrayList<>();
        for (PriceMapping m : config.mappings()) {
            JsonNode market = root.path("stats").path(m.path() + "-rls");
            if (market.isMissingNode()) {
                results.add(QuoteResult.failed(m, "بازار " + m.path() + "-rls در پاسخ نوبیتکس نیست."));
                continue;
            }
            Optional<BigDecimal> raw = PriceJson.positiveNumber(market.path("latest"))
                    .or(() -> PriceJson.positiveNumber(market.path("mark")))
                    .or(() -> PriceJson.positiveNumber(market.path("bestSell")));
            if (raw.isEmpty()) {
                results.add(QuoteResult.failed(m, "قیمتی برای " + m.path() + " منتشر نشده است."));
                continue;
            }
            BigDecimal value = m.multiplier() == null ? raw.get() : raw.get().multiply(m.multiplier());
            results.add(QuoteResult.ok(m, raw.get(), PriceUnit.RIAL.toToman(value)));
        }
        return results;
    }
}
