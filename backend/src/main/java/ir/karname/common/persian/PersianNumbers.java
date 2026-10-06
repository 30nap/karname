package ir.karname.common.persian;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parsing of numbers written by Iranian users and banks (Persian digits, various separators). */
public final class PersianNumbers {

    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    private PersianNumbers() {
    }

    /**
     * Parses a decimal number written with Persian or Latin digits, e.g. "1,250,000" or "2.5" in
     * either digit set. Thousands separators (comma, Arabic thousands separator U+066C, Arabic
     * comma U+060C, ZWNJ and spaces) are ignored; the Arabic decimal separator U+066B and "."
     * are decimal points.
     */
    public static Optional<BigDecimal> parseDecimal(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = PersianText.normalizeDigits(text.trim())
                .replace('٫', '.')
                .replace("٬", "")
                .replace("،", "")
                .replace(",", "")
                .replace("‌", "")
                .replaceAll("\\s+", "")
                .replace('−', '-');
        if (!NUMBER.matcher(s).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(s));
    }

    /** Same as {@link #parseDecimal} but throws {@link IllegalArgumentException} on invalid input. */
    public static BigDecimal parseDecimalOrThrow(String text) {
        return parseDecimal(text).orElseThrow(() -> new IllegalArgumentException("Invalid number: " + text));
    }
}
