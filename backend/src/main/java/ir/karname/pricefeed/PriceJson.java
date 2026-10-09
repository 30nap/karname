package ir.karname.pricefeed;

import ir.karname.common.Decimals;
import ir.karname.common.persian.PersianNumbers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;

/** JSON helpers for price responses: decimals stay exact and numbers may arrive as text. */
final class PriceJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private PriceJson() {
    }

    static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new PriceFetchException("پاسخ سرویس JSON معتبر نیست.", e);
        }
    }

    /** A positive number from a JSON number or a string such as "1,250,000" or "۱۲۵۰۰۰۰". */
    static Optional<BigDecimal> positiveNumber(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        Optional<BigDecimal> value = node.isNumber() ? Optional.of(node.decimalValue())
                : node.isString() ? PersianNumbers.parseDecimal(node.asString()) : Optional.empty();
        // a hostile or broken source must not hand the price math an extreme number
        return value.filter(v -> Decimals.isReasonable(v) && v.signum() > 0);
    }

    static String describe(JsonNode node) {
        String text = node.isString() ? node.asString() : node.toString();
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }
}
