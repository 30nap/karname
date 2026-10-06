package ir.karname.common.persian;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Server-side Persian number text for messages: «۱۲٬۵۰۰٬۰۰۰». */
public final class PersianFormat {

    private static final char GROUP = '٬';

    private PersianFormat() {
    }

    /** A whole number with Persian digits and thousands separators. */
    public static String number(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        String digits = rounded.abs().toPlainString();
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(GROUP);
            }
            sb.append(digits.charAt(i));
        }
        return (rounded.signum() < 0 ? "−" : "") + PersianText.toPersianDigits(sb.toString());
    }

    public static String number(long value) {
        return number(BigDecimal.valueOf(value));
    }

    /** An amount in Toman, shown in the user's unit: «۱۲٬۵۰۰٬۰۰۰ تومان» or «۱۲۵٬۰۰۰٬۰۰۰ ریال». */
    public static String money(BigDecimal toman, boolean rial) {
        return rial ? number(toman.multiply(BigDecimal.TEN)) + " ریال" : number(toman) + " تومان";
    }
}
