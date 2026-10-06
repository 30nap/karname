package ir.karname.pricefeed;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Any HTTP API that returns JSON: each mapping names a JSON Pointer to read, so a provider that
 * changes its format is fixed from the admin settings without a new release.
 */
@Component
class JsonPriceProvider implements PriceProvider {

    private final PriceHttpClient http;

    JsonPriceProvider(PriceHttpClient http) {
        this.http = http;
    }

    @Override
    public PriceSourceKind kind() {
        return PriceSourceKind.JSON;
    }

    @Override
    public List<QuoteResult> fetch(SourceConfig config) {
        JsonNode root = PriceJson.parse(http.get(URI.create(config.url()), config.headers()));
        List<QuoteResult> results = new ArrayList<>();
        for (PriceMapping m : config.mappings()) {
            JsonNode node = root.at(m.path());
            if (node.isMissingNode() || node.isNull()) {
                results.add(QuoteResult.failed(m, "مسیر " + m.path() + " در پاسخ پیدا نشد."));
                continue;
            }
            Optional<BigDecimal> raw = PriceJson.positiveNumber(node);
            if (raw.isEmpty()) {
                results.add(QuoteResult.failed(m, "مقدار «" + PriceJson.describe(node) + "» عدد مثبت نیست."));
                continue;
            }
            BigDecimal value = m.multiplier() == null ? raw.get() : raw.get().multiply(m.multiplier());
            results.add(QuoteResult.ok(m, raw.get(), config.unit().toToman(value)));
        }
        return results;
    }
}
