package ir.karname.ai.guard;

import ir.karname.common.persian.PersianText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks identifiers before text leaves for an AI provider: card numbers, IBANs, mobile numbers,
 * national ids and long account numbers keep only their last four digits, which is all that
 * matching a bank SMS to an account needs. Amounts are left alone.
 */
public final class Privacy {

    private static final Pattern IBAN = Pattern.compile("(?i)\\bIR\\s?\\d{2}(?:[\\s-]?\\d){18,22}\\b");
    private static final Pattern CARD = Pattern.compile("(?<![\\d*])\\d{4}([\\s-]?)\\d{4}\\1\\d{4}\\1(\\d{4})(?![\\d*])");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(?:\\+98|0098|0)9\\d{5}(\\d{4})(?!\\d)");
    private static final Pattern NATIONAL_ID = Pattern.compile("(کد\\s*ملی|شناسه\\s*ملی)\\s*[:：]?\\s*\\d{10}");
    /** Account numbers in groups, such as 0123-45-6789012-1 or 123.456.789012.1. */
    private static final Pattern GROUPED_ACCOUNT = Pattern.compile("(?<![\\d/])\\d{2,4}[-.]\\d{1,4}[-.]\\d{5,12}(?:[-.]\\d{1,3})?(?![\\d/])");
    /** Digit runs too long to be an amount. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d,٬])\\d{9,}(\\d{4})(?![\\d,٬])");

    private Privacy() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = PersianText.normalizeDigits(text);
        t = replace(IBAN.matcher(t), m -> "IR…" + lastDigits(m.group(), 4));
        t = replace(CARD.matcher(t), m -> "****-" + m.group(2));
        t = replace(MOBILE.matcher(t), m -> "09******" + m.group(1));
        t = replace(NATIONAL_ID.matcher(t), m -> m.group(1) + " ****");
        t = replace(GROUPED_ACCOUNT.matcher(t), m -> "****" + lastDigits(m.group(), 4));
        t = replace(LONG_NUMBER.matcher(t), m -> "****" + m.group(1));
        return t;
    }

    private static String lastDigits(String text, int count) {
        String digits = text.replaceAll("\\D", "");
        return digits.length() <= count ? digits : digits.substring(digits.length() - count);
    }

    private static String replace(Matcher m, java.util.function.Function<Matcher, String> replacement) {
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Text placed inside a tag of the prompt (e.g. {@code <sms>}) with angle brackets neutralised,
     * so it cannot close the tag and pose as instructions.
     */
    public static String quote(String text) {
        return text == null ? "" : text.replace('<', '‹').replace('>', '›');
    }
}
