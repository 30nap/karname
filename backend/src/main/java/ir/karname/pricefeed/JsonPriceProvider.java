package ir.karname.pricefeed;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
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
        String body = http.get(config.uri(config.url()), config.requestHeaders(), config.secrets());
        JsonNode root = PriceJson.parse(body);
        List<QuoteResult> results = new ArrayList<>();
        int missing = 0;
        for (PriceMapping m : config.mappings()) {
            JsonNode node = PriceJson.at(root, m.path());
            if (node.isMissingNode() || node.isNull()) {
                results.add(QuoteResult.failed(m, "مسیر " + m.path() + " در پاسخ پیدا نشد."));
                missing++;
                continue;
            }
            Optional<BigDecimal> raw = PriceJson.positiveNumber(node);
            if (raw.isEmpty()) {
                results.add(QuoteResult.failed(m, "مقدار «" + PriceJson.describe(node) + "» عدد مثبت نیست."));
                continue;
            }
            BigDecimal value = m.multiplier() == null ? raw.get() : raw.get().multiply(m.multiplier());
            results.add(QuoteResult.ok(m, raw.get(), m.unitOr(config.unit()).toToman(value)));
        }
        if (missing == results.size()) {
            // nothing matched: an error answered with 200 ({"success": false, ...}) or a new format; say what came back
            String detail = PriceHttpClient.detail(body, config.secrets());
            throw new PriceFetchException("هیچ‌کدام از مسیرها در پاسخ نبود؛ شاید سرویس خطا داده یا قالب پاسخ عوض شده است"
                    + (detail.isEmpty() ? "." : ": " + detail));
        }
        return results;
    }
}
