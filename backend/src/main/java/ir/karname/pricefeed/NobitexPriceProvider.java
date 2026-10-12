package ir.karname.pricefeed;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nobitex market statistics: {@code GET /market/stats?srcCurrency=btc,usdt&dstCurrency=rls},
 * public and keyless (host apiv2.nobitex.ir since mid-2025; api.nobitex.ir no longer resolves).
 * Prices are in Rial; the last trade is used, then the mark price, then the best ask. A closed
 * market is not a price: its figures are frozen. A USDT mapping onto USD gives a usable
 * free-market dollar rate.
 * <p>
 * Nobitex refuses the whole request (HTTP 400) when one symbol is unknown, as happens when a coin is
 * renamed or delisted (Toncoin became Gram, "gram", in June 2026); the symbols are then asked one by
 * one, so the others still arrive and the unknown one is named. "ton" is read as "gram".
 */
@Component
class NobitexPriceProvider implements PriceProvider {

    static final String DEFAULT_URL = "https://apiv2.nobitex.ir";
    /** Nobitex asks bots to name themselves this way. */
    static final String USER_AGENT = "TraderBot/Karname";
    /** Coins Nobitex renamed: the old symbol in a mapping is asked under the new one. */
    private static final Map<String, String> RENAMED = Map.of("ton", "gram");

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
        config.mappings().forEach(m -> symbols.add(symbol(m)));
        String base = config.url() == null || config.url().isBlank() ? DEFAULT_URL : config.url().replaceAll("/+$", "");

        Map<String, JsonNode> markets = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        Map<String, String> unasked = new LinkedHashMap<>();
        try {
            markets.putAll(stats(config, base, symbols));
        } catch (PriceFetchException e) {
            if (e.status() != 400 || symbols.size() < 2) {
                throw e;
            }
            PriceFetchException stopped = null;
            for (String symbol : symbols) {
                if (stopped != null) {
                    unasked.put(symbol, stopped.getMessage());
                    continue;
                }
                try {
                    markets.putAll(stats(config, base, Set.of(symbol)));
                } catch (PriceFetchException one) {
                    if (one.status() == 400) {
                        refused.put(symbol, one.getMessage());
                    } else {
                        // a rate limit or an outage: stop asking, keep what already arrived
                        stopped = one;
                        unasked.put(symbol, one.getMessage());
                    }
                }
            }
            if (markets.isEmpty()) {
                throw stopped != null ? stopped : e;
            }
        }

        List<QuoteResult> results = new ArrayList<>();
        for (PriceMapping m : config.mappings()) {
            String symbol = symbol(m);
            if (unasked.containsKey(symbol)) {
                results.add(QuoteResult.failed(m, unasked.get(symbol)));
                continue;
            }
            if (refused.containsKey(symbol)) {
                results.add(QuoteResult.failed(m, "نوبیتکس نماد " + symbol + " را نپذیرفت (شاید نامش عوض شده یا حذف شده است): "
                        + refused.get(symbol)));
                continue;
            }
            JsonNode market = markets.get(symbol + "-rls") != null || symbol.equals(m.path())
                    ? markets.get(symbol + "-rls") : markets.get(m.path() + "-rls");
            if (market == null || market.isMissingNode()) {
                results.add(QuoteResult.failed(m, "بازار " + symbol + "-rls در پاسخ نوبیتکس نیست."));
                continue;
            }
            if (market.path("isClosed").asBoolean(false)) {
                results.add(QuoteResult.failed(m, "بازار " + symbol + "-rls در نوبیتکس بسته است؛ قیمت آن به‌روز نیست."));
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

    private static String symbol(PriceMapping m) {
        return RENAMED.getOrDefault(m.path(), m.path());
    }

    /** The markets of one request, by name ("usdt-rls"). */
    private Map<String, JsonNode> stats(SourceConfig config, String base, Set<String> symbols) {
        String address = base + "/market/stats?srcCurrency=" + String.join(",", symbols) + "&dstCurrency=rls";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.putAll(config.requestHeaders());
        JsonNode root = PriceJson.parse(http.get(config.uri(address), headers, config.secrets()));
        if (!"ok".equals(root.path("status").asString(""))) {
            String message = root.path("message").asString("");
            String code = root.path("code").asString("");
            // only a refused symbol is worth asking again one by one; maintenance or a rate limit is not
            int status = code.matches("(?i).*(symbol|currency|market).*") ? 400 : 0;
            throw new PriceFetchException("نوبیتکس خطا برگرداند" + (message.isBlank() ? (code.isBlank() ? "." : ": " + code) : ": " + message), status);
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        root.path("stats").properties().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }
}
