package ir.karname.common.jalali;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Conversion between the Jalali (Solar Hijri) and Gregorian calendars.
 *
 * <p>Port of the jalaali-js algorithm (Borkowski's leap-year breaks), which follows the
 * astronomical calendar in use in Iran for the supported range.
 */
final class JalaliCalendar {

    static final int MIN_YEAR = 1;
    static final int MAX_YEAR = 3177;

    private static final int[] BREAKS = {
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    };

    private JalaliCalendar() {
    }

    /** Gregorian date of 1 Farvardin of the given Jalali year. */
    static LocalDate nowruz(int jy) {
        checkYear(jy);
        int gy = jy + 621;
        int leapJ = -14;
        int jp = BREAKS[0];
        int jump = 0;
        for (int i = 1; i < BREAKS.length; i++) {
            int jm = BREAKS[i];
            jump = jm - jp;
            if (jy < jm) {
                break;
            }
            leapJ += (jump / 33) * 8 + (jump % 33) / 4;
            jp = jm;
        }
        int n = jy - jp;
        leapJ += (n / 33) * 8 + ((n % 33) + 3) / 4;
        if (jump % 33 == 4 && jump - n == 4) {
            leapJ += 1;
        }
        int leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150;
        int march = 20 + leapJ - leapG;
        return LocalDate.of(gy, 3, 1).plusDays(march - 1L);
    }

    /** Number of years since the last leap year: 0 means {@code jy} itself is a leap year. */
    static int yearsSinceLeap(int jy) {
        checkYear(jy);
        int jp = BREAKS[0];
        int jump = 0;
        for (int i = 1; i < BREAKS.length; i++) {
            int jm = BREAKS[i];
            jump = jm - jp;
            if (jy < jm) {
                break;
            }
            jp = jm;
        }
        int n = jy - jp;
        if (jump - n < 6) {
            n = n - jump + ((jump + 4) / 33) * 33;
        }
        int leap = (((n + 1) % 33) - 1) % 4;
        return leap == -1 ? 4 : leap;
    }

    static boolean isLeapYear(int jy) {
        return yearsSinceLeap(jy) == 0;
    }

    static int monthLength(int jy, int jm) {
        if (jm < 1 || jm > 12) {
            throw new IllegalArgumentException("Invalid Jalali month: " + jm);
        }
        if (jm <= 6) {
            return 31;
        }
        if (jm <= 11) {
            return 30;
        }
        return isLeapYear(jy) ? 30 : 29;
    }

    /** Days elapsed from 1 Farvardin to the given month/day (0-based). */
    static int dayOfYearIndex(int jm, int jd) {
        return (jm <= 6 ? (jm - 1) * 31 : 186 + (jm - 7) * 30) + jd - 1;
    }

    static LocalDate toGregorian(int jy, int jm, int jd) {
        return nowruz(jy).plusDays(dayOfYearIndex(jm, jd));
    }

    static int[] fromGregorian(LocalDate date) {
        int jy = date.getYear() - 621;
        LocalDate farvardin1 = nowruz(jy);
        long k = ChronoUnit.DAYS.between(farvardin1, date);
        if (k < 0) {
            jy -= 1;
            farvardin1 = nowruz(jy);
            k = ChronoUnit.DAYS.between(farvardin1, date);
        }
        int index = (int) k;
        if (index <= 185) {
            return new int[] {jy, 1 + index / 31, index % 31 + 1};
        }
        index -= 186;
        return new int[] {jy, 7 + index / 30, index % 30 + 1};
    }

    private static void checkYear(int jy) {
        if (jy < MIN_YEAR || jy >= MAX_YEAR) {
            throw new IllegalArgumentException("Jalali year out of supported range: " + jy);
        }
    }
}
