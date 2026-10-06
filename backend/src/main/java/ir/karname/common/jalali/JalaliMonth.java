package ir.karname.common.jalali;

import ir.karname.common.persian.PersianText;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Jalali year-month, the unit for budgets, reports and recurrence. */
public record JalaliMonth(int year, int month) implements Comparable<JalaliMonth> {

    private static final Pattern PATTERN = Pattern.compile("^\\s*(\\d{1,4})\\s*[/\\-.]\\s*(\\d{1,2})\\s*$");

    public JalaliMonth {
        if (year < JalaliCalendar.MIN_YEAR || year >= JalaliCalendar.MAX_YEAR) {
            throw new IllegalArgumentException("Jalali year out of range: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid Jalali month: " + month);
        }
    }

    public static JalaliMonth of(int year, int month) {
        return new JalaliMonth(year, month);
    }

    public static JalaliMonth from(LocalDate date) {
        return JalaliDate.from(date).yearMonth();
    }

    /** Parses {@code yyyy-mm} or {@code yyyy/mm}, accepting Persian digits. */
    public static JalaliMonth parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Jalali month is null");
        }
        Matcher m = PATTERN.matcher(PersianText.normalizeDigits(text));
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid Jalali month: " + text);
        }
        return new JalaliMonth(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    public int lengthOfMonth() {
        return JalaliCalendar.monthLength(year, month);
    }

    public JalaliDate atDay(int day) {
        return new JalaliDate(year, month, day);
    }

    public JalaliDate firstDay() {
        return atDay(1);
    }

    public JalaliDate lastDay() {
        return atDay(lengthOfMonth());
    }

    /** First Gregorian day of the month (inclusive). */
    public LocalDate startDate() {
        return firstDay().toGregorian();
    }

    /** Last Gregorian day of the month (inclusive). */
    public LocalDate endDate() {
        return lastDay().toGregorian();
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate()) && !date.isAfter(endDate());
    }

    public JalaliMonth plusMonths(long months) {
        long index = (long) year * 12 + (month - 1) + months;
        return new JalaliMonth((int) Math.floorDiv(index, 12), (int) Math.floorMod(index, 12) + 1);
    }

    public JalaliMonth previous() {
        return plusMonths(-1);
    }

    public JalaliMonth next() {
        return plusMonths(1);
    }

    /** Number of months from this month to {@code other} (positive if other is later). */
    public long monthsUntil(JalaliMonth other) {
        return ((long) other.year * 12 + other.month) - ((long) year * 12 + month);
    }

    /** Persian label, e.g. «مهر ۱۴۰۵». */
    public String label() {
        return PersianText.toPersianDigits(JalaliDate.monthName(month) + " " + year);
    }

    @Override
    public int compareTo(JalaliMonth other) {
        int c = Integer.compare(year, other.year);
        return c != 0 ? c : Integer.compare(month, other.month);
    }

    /** Canonical form with Latin digits: {@code 1405-07}. */
    @Override
    public String toString() {
        return String.format("%04d-%02d", year, month);
    }
}
