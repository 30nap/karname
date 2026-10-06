package ir.karname.common.persian;

import java.util.Locale;

/** Normalization helpers for Persian text entered by users, banks and LLMs. */
public final class PersianText {

    private static final char ZWNJ = '‌';

    private PersianText() {
    }

    /** Converts Persian (U+06F0..U+06F9) and Arabic-Indic (U+0660..U+0669) digits to ASCII digits. */
    public static String normalizeDigits(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(asciiDigit(text.charAt(i)));
        }
        return sb.toString();
    }

    /** Converts ASCII digits to Persian digits (for display strings). */
    public static String toPersianDigits(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= '0' && c <= '9' ? (char) ('۰' + (c - '0')) : c);
        }
        return sb.toString();
    }

    /**
     * Unifies Arabic letter variants with their Persian forms (Arabic Yeh/Alef Maksura to Persian
     * Yeh, Arabic Kaf to Keheh, Teh Marbuta to Heh) and removes tatweel and Arabic diacritics.
     * Digits are kept as typed: this is text the user will see again.
     */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'ي', 'ى' -> sb.append('ی');
                case 'ك' -> sb.append('ک');
                case 'ة' -> sb.append('ه');
                case 'ـ' -> {
                    // tatweel (kashida) carries no meaning
                }
                default -> {
                    if (!isArabicDiacritic(c)) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Normal form used for searching and matching: {@link #normalize}, ASCII digits, ZWNJ treated as
     * a space, whitespace collapsed and Latin text lower-cased.
     */
    public static String normalizeForSearch(String text) {
        if (text == null) {
            return "";
        }
        String n = normalizeDigits(normalize(text)).replace(ZWNJ, ' ');
        return n.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Normalizes, trims and collapses whitespace; returns {@code null} for blank input. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String t = normalize(text).replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    private static char asciiDigit(char c) {
        if (c >= '۰' && c <= '۹') {
            return (char) ('0' + (c - '۰'));
        }
        if (c >= '٠' && c <= '٩') {
            return (char) ('0' + (c - '٠'));
        }
        return c;
    }

    private static boolean isArabicDiacritic(char c) {
        return (c >= 'ً' && c <= 'ٟ') || c == 'ٰ';
    }
}
