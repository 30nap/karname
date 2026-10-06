package ir.karname.common.web;

import ir.karname.common.jalali.JalaliMonth;

import java.time.LocalDate;

/** Parses a Jalali month request parameter ("1405-07"); absent means the month of {@code today}. */
public final class MonthParam {

    private MonthParam() {
    }

    public static JalaliMonth parse(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return JalaliMonth.from(today);
        }
        try {
            return JalaliMonth.parse(text.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("error.invalidMonth");
        }
    }
}
