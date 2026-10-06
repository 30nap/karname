package ir.karname.recurring;

import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Occurrence dates on the Jalali calendar. A monthly rule on day 31 falls on the last day of
 * shorter months; weekdays count from Saturday (0) to Friday (6).
 */
record Recurrence(Frequency frequency, int interval, Integer dayOfMonth, Integer dayOfWeek, Integer monthOfYear, LocalDate start,
        LocalDate end) {

    /** Saturday-first weekday index of a date. */
    static int weekday(LocalDate date) {
        return (date.getDayOfWeek().getValue() + 1) % 7;
    }

    /** Occurrences within [from, to], oldest first. */
    List<LocalDate> between(LocalDate from, LocalDate to) {
        LocalDate lower = from.isAfter(start) ? from : start;
        LocalDate upper = end != null && end.isBefore(to) ? end : to;
        List<LocalDate> result = new ArrayList<>();
        if (lower.isAfter(upper)) {
            return result;
        }
        switch (frequency) {
            case WEEKLY -> {
                int target = dayOfWeek != null ? dayOfWeek : weekday(start);
                LocalDate first = start.plusDays(Math.floorMod(target - weekday(start), 7));
                long step = 7L * interval;
                long skip = first.isBefore(lower) ? (ChronoUnit.DAYS.between(first, lower) + step - 1) / step : 0;
                for (LocalDate d = first.plusDays(skip * step); !d.isAfter(upper); d = d.plusDays(step)) {
                    result.add(d);
                }
            }
            case MONTHLY -> {
                JalaliDate anchor = JalaliDate.from(start);
                int day = dayOfMonth != null ? dayOfMonth : anchor.day();
                JalaliMonth first = JalaliMonth.of(anchor.year(), anchor.month());
                long gap = Math.max(0, first.monthsUntil(JalaliMonth.from(lower)));
                for (JalaliMonth m = first.plusMonths((gap / interval) * interval); !m.startDate().isAfter(upper); m = m.plusMonths(interval)) {
                    add(result, m.atDay(Math.min(day, m.lengthOfMonth())).toGregorian(), lower, upper);
                }
            }
            case YEARLY -> {
                JalaliDate anchor = JalaliDate.from(start);
                int month = monthOfYear != null ? monthOfYear : anchor.month();
                int day = dayOfMonth != null ? dayOfMonth : anchor.day();
                int firstYear = anchor.year();
                int lowerYear = JalaliDate.from(lower).year();
                int skip = Math.max(0, (lowerYear - firstYear) / interval);
                for (int y = firstYear + skip * interval; ; y += interval) {
                    LocalDate d = JalaliDate.of(y, month, Math.min(day, JalaliMonth.of(y, month).lengthOfMonth())).toGregorian();
                    if (d.isAfter(upper)) {
                        break;
                    }
                    add(result, d, lower, upper);
                }
            }
        }
        return result;
    }

    private static void add(List<LocalDate> result, LocalDate date, LocalDate lower, LocalDate upper) {
        if (!date.isBefore(lower) && !date.isAfter(upper)) {
            result.add(date);
        }
    }
}
