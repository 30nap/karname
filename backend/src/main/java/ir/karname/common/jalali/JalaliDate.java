package ir.karname.common.jalali;

import ir.karname.common.persian.PersianText;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable date in the Jalali (Solar Hijri) calendar.
 *
 * <p>Storage and arithmetic use {@link LocalDate}; this type exists for parsing, display and
 * month boundaries, which in this app always follow the Jalali calendar.
 */
public record JalaliDate(int year, int month, int day) implements Comparable<JalaliDate> {

    private static final Pattern PATTERN = Pattern.compile("^\\s*(\\d{1,4})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*$");

    private static final String[] MONTH_NAMES = {
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    public JalaliDate {
        if (year < JalaliCalendar.MIN_YEAR || year >= JalaliCalendar.MAX_YEAR) {
            throw new IllegalArgumentException("Jalali year out of range: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid Jalali month: " + month);
        }
        if (day < 1 || day > JalaliCalendar.monthLength(year, month)) {
            throw new IllegalArgumentException("Invalid day " + day + " for Jalali month " + year + "/" + month);
        }
    }

    public static JalaliDate of(int year, int month, int day) {
        return new JalaliDate(year, month, day);
    }

    public static JalaliDate from(LocalDate date) {
        int[] j = JalaliCalendar.fromGregorian(date);
        return new JalaliDate(j[0], j[1], j[2]);
    }

    /**
     * Parses {@code yyyy/mm/dd} (also with {@code -} or {@code .} separators), accepting
     * Persian and Arabic-Indic digits.
     *
     * @throws IllegalArgumentException if the text is not a valid Jalali date
     */
    public static JalaliDate parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Jalali date is null");
        }
        Matcher m = PATTERN.matcher(PersianText.normalizeDigits(text));
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid Jalali date: " + text);
        }
        return new JalaliDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public static boolean isLeapYear(int year) {
        return JalaliCalendar.isLeapYear(year);
    }

    public static int lengthOfMonth(int year, int month) {
        return JalaliCalendar.monthLength(year, month);
    }

    public static String monthName(int month) {
        return MONTH_NAMES[month - 1];
    }

    public LocalDate toGregorian() {
        return JalaliCalendar.toGregorian(year, month, day);
    }

    public int lengthOfMonth() {
        return JalaliCalendar.monthLength(year, month);
    }

    public boolean isLeapYear() {
        return JalaliCalendar.isLeapYear(year);
    }

    public JalaliMonth yearMonth() {
        return new JalaliMonth(year, month);
    }

    public String monthName() {
        return monthName(month);
    }

    public DayOfWeek dayOfWeek() {
        return toGregorian().getDayOfWeek();
    }

    /** Persian weekday name, e.g. «سه‌شنبه». */
    public String dayOfWeekName() {
        return switch (dayOfWeek()) {
            case SATURDAY -> "شنبه";
            case SUNDAY -> "یکشنبه";
            case MONDAY -> "دوشنبه";
            case TUESDAY -> "سه‌شنبه";
            case WEDNESDAY -> "چهارشنبه";
            case THURSDAY -> "پنجشنبه";
            case FRIDAY -> "جمعه";
        };
    }

    public JalaliDate plusDays(long days) {
        return from(toGregorian().plusDays(days));
    }

    /** Adds months, clamping the day to the target month's length (e.g. 31 Shahrivar + 1 month = 30 Mehr). */
    public JalaliDate plusMonths(long months) {
        JalaliMonth target = yearMonth().plusMonths(months);
        return target.atDay(Math.min(day, target.lengthOfMonth()));
    }

    public JalaliDate withDay(int dayOfMonth) {
        return new JalaliDate(year, month, dayOfMonth);
    }

    /** Human-readable form, e.g. «۱۴ مهر ۱۴۰۵». */
    public String toDisplayString() {
        return PersianText.toPersianDigits(day + " " + monthName() + " " + year);
    }

    @Override
    public int compareTo(JalaliDate other) {
        int c = Integer.compare(year, other.year);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(month, other.month);
        return c != 0 ? c : Integer.compare(day, other.day);
    }

    /** ISO-like form with Latin digits: {@code 1405/07/14}. */
    @Override
    public String toString() {
        return String.format("%04d/%02d/%02d", year, month, day);
    }
}
