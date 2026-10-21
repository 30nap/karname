package ir.karname.ai.propose;

import ir.karname.ai.capture.DraftBuilder.Scale;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.persian.PersianText;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The conventions every proposal tool shares, as the transaction drafts already use them: amounts
 * as the user said them with a scale word (the server multiplies, and divides Rial by ten), Jalali
 * dates, and references to the user's records by id or by name.
 */
final class Inputs {

    static final BigDecimal MAX = new BigDecimal("1e15");
    private static final List<String> SCALES = Arrays.stream(Scale.values()).map(Enum::name).toList();

    private Inputs() {
    }

    // ---------------------------------------------------------------- schema

    /** An amount in Toman: {@code field}, {@code field_scale} and {@code field_unit}. */
    static JsonSchema.ObjectBuilder money(JsonSchema.ObjectBuilder b, String field, String what, boolean required) {
        JsonSchema number = JsonSchema.number(what + ": the number as the user said it, before any scale word («۲۰۰ میلیون» → 200)").min(0);
        (required ? b.required(field, number) : b.optional(field, number))
                .optional(field + "_scale", JsonSchema.enumOf("The scale word after the number: none → ONE (default), «هزار» → THOUSAND, "
                        + "«میلیون» → MILLION, «میلیارد» → BILLION. Colloquial «تومن» after a small number means thousand for everyday "
                        + "amounts and million for big ones (a loan, a car)", SCALES))
                .optional(field + "_unit", JsonSchema.enumOf("TOMAN (default), or RIAL when the user said ریال", List.of("TOMAN", "RIAL")));
        return b;
    }

    /** A number of units (shares, grams, dollars): {@code field} and {@code field_scale}. */
    static JsonSchema.ObjectBuilder quantity(JsonSchema.ObjectBuilder b, String field, String what, boolean required) {
        JsonSchema number = JsonSchema.number(what + ": the number as the user said it, before any scale word").min(0);
        (required ? b.required(field, number) : b.optional(field, number))
                .optional(field + "_scale", JsonSchema.enumOf("Scale word after the number: ONE (default), THOUSAND, MILLION or BILLION", SCALES));
        return b;
    }

    static JsonSchema date(String what) {
        return JsonSchema.string(what + ", as a Jalali date yyyy/mm/dd worked out from today's date in the context").maxLength(10);
    }

    static JsonSchema reference(String what) {
        return JsonSchema.string(what + ": its id from a list tool, or its name as the user said it").maxLength(100);
    }

    static JsonSchema replacesPrevious() {
        return JsonSchema.bool("True when this corrects cards of this kind you proposed earlier in this conversation that the user "
                + "has not confirmed: those are withdrawn, so include everything that should remain");
    }

    // ---------------------------------------------------------------- reading

    /** An amount in Toman, or null when the field is absent. */
    static BigDecimal toman(JsonNode in, String field) {
        if (!in.hasNonNull(field)) {
            return null;
        }
        BigDecimal value = scaled(in, field);
        if ("RIAL".equals(text(in, field + "_unit"))) {
            value = value.movePointLeft(1);
        }
        return check(field, value);
    }

    /** A number of units, or null when the field is absent. */
    static BigDecimal quantity(JsonNode in, String field) {
        return in.hasNonNull(field) ? check(field, scaled(in, field)) : null;
    }

    private static BigDecimal scaled(JsonNode in, String field) {
        BigDecimal value = in.get(field).decimalValue();
        String scale = text(in, field + "_scale");
        return scale == null ? value : value.multiply(Scale.valueOf(scale).factor());
    }

    private static BigDecimal check(String field, BigDecimal value) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + ": must be more than zero");
        }
        if (value.compareTo(MAX) >= 0) {
            throw new IllegalArgumentException(field + ": implausibly large; check the scale word");
        }
        BigDecimal v = value.setScale(Math.min(Math.max(value.stripTrailingZeros().scale(), 0), 8), RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    static LocalDate date(JsonNode in, String field) {
        String text = text(in, field);
        if (text == null) {
            return null;
        }
        try {
            return JalaliDate.parse(PersianText.normalizeDigits(text).replace('-', '/')).toGregorian();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": «" + text + "» is not a Jalali date as yyyy/mm/dd");
        }
    }

    static JalaliMonth month(JsonNode in, String field, JalaliMonth fallback) {
        String text = text(in, field);
        if (text == null) {
            return fallback;
        }
        try {
            return JalaliMonth.parse(PersianText.normalizeDigits(text).replace('/', '-'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": «" + text + "» is not a Jalali month as yyyy-mm");
        }
    }

    /** Cleaned text, or null when absent or blank. */
    static String text(JsonNode in, String field) {
        return in.hasNonNull(field) ? PersianText.clean(in.get(field).asString()) : null;
    }

    static Integer integer(JsonNode in, String field) {
        return in.hasNonNull(field) ? in.get(field).asInt() : null;
    }

    /** Whether the field holds a number above zero. */
    static boolean positive(JsonNode in, String field) {
        return in.hasNonNull(field) && in.get(field).isNumber() && in.get(field).decimalValue().signum() > 0;
    }

    static boolean flag(JsonNode in, String field) {
        return in.path(field).asBoolean(false);
    }

    static List<String> texts(JsonNode in, String field) {
        List<String> out = new ArrayList<>();
        if (in.hasNonNull(field)) {
            in.get(field).valueStream().map(n -> PersianText.clean(n.asString())).filter(s -> s != null).forEach(out::add);
        }
        return out;
    }

    static <E extends Enum<E>> E enumValue(JsonNode in, String field, Class<E> type) {
        String text = text(in, field);
        return text == null ? null : Enum.valueOf(type, text);
    }
}
