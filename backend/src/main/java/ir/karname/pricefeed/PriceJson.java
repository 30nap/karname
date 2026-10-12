package ir.karname.pricefeed;

import ir.karname.common.Decimals;
import ir.karname.common.persian.PersianNumbers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON helpers for price responses: decimals stay exact and numbers may arrive as text. */
final class PriceJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    /** A path segment choosing a list element by one of its fields: {@code [symbol=USD]}. */
    private static final Pattern MATCH = Pattern.compile("^\\[([^=\\]]+)=([^\\]]*)]$");

    private PriceJson() {
    }

    static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new PriceFetchException("پاسخ سرویس JSON معتبر نیست.", e);
        }
    }

    /**
     * The node at a JSON Pointer (RFC 6901), where a segment may also be {@code [field=value]}: the
     * first element of a list whose {@code field} equals {@code value} (ignoring case), for answers
     * such as {@code {"gold":[{"symbol":"IR_GOLD_18K","price":...}]}} read with
     * {@code /gold/[symbol=IR_GOLD_18K]/price}, whatever the order of the list.
     */
    static JsonNode at(JsonNode root, String pointer) {
        if (pointer == null || !pointer.startsWith("/")) {
            return MissingNode.getInstance();
        }
        JsonNode node = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String token = raw.replace("~1", "/").replace("~0", "~");
            Matcher match = MATCH.matcher(token);
            if (node.isArray() && match.matches()) {
                node = find(node, match.group(1).strip(), match.group(2).strip());
            } else if (node.isArray()) {
                node = token.matches("\\d{1,6}") ? node.path(Integer.parseInt(token)) : MissingNode.getInstance();
            } else if (node.isObject()) {
                node = node.path(token);
            } else {
                return MissingNode.getInstance();
            }
            if (node.isMissingNode()) {
                return node;
            }
        }
        return node;
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode element : list) {
            JsonNode candidate = element.path(field);
            if ((candidate.isString() || candidate.isNumber()) && candidate.asString().equalsIgnoreCase(value)) {
                return element;
            }
        }
        return MissingNode.getInstance();
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
