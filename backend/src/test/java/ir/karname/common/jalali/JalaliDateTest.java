package ir.karname.common.jalali;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JalaliDateTest {

    @ParameterizedTest
    @CsvSource({
            "1405, 1, 1, 2026-03-21",
            "1405, 7, 14, 2026-10-06",
            "1404, 1, 1, 2025-03-21",
            "1403, 12, 30, 2025-03-20",
            "1403, 1, 1, 2024-03-20",
            "1399, 12, 30, 2021-03-20",
            "1357, 11, 22, 1979-02-11",
            "1348, 4, 30, 1969-07-21",
            "1405, 6, 31, 2026-09-22",
            "1405, 7, 1, 2026-09-23",
            "1405, 12, 29, 2027-03-20",
    })
    void convertsKnownDates(int y, int m, int d, LocalDate gregorian) {
        JalaliDate jalali = JalaliDate.of(y, m, d);
        assertThat(jalali.toGregorian()).isEqualTo(gregorian);
        assertThat(JalaliDate.from(gregorian)).isEqualTo(jalali);
    }

    @Test
    void roundTripsEveryDayFrom1300To1500() {
        LocalDate start = JalaliDate.of(1300, 1, 1).toGregorian();
        LocalDate end = JalaliDate.of(1500, 12, JalaliDate.lengthOfMonth(1500, 12)).toGregorian();
        JalaliDate previous = null;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            JalaliDate j = JalaliDate.from(d);
            assertThat(j.toGregorian()).isEqualTo(d);
            if (previous != null) {
                // consecutive Gregorian days map to consecutive Jalali days
                assertThat(previous.compareTo(j)).isNegative();
                boolean sameMonth = previous.year() == j.year() && previous.month() == j.month();
                if (sameMonth) {
                    assertThat(j.day()).isEqualTo(previous.day() + 1);
                } else {
                    assertThat(j.day()).isEqualTo(1);
                    assertThat(previous.day()).isEqualTo(previous.lengthOfMonth());
                }
            }
            previous = j;
        }
    }

    @Test
    void knowsLeapYears() {
        assertThat(JalaliDate.isLeapYear(1399)).isTrue();
        assertThat(JalaliDate.isLeapYear(1403)).isTrue();
        assertThat(JalaliDate.isLeapYear(1408)).isTrue();
        assertThat(JalaliDate.isLeapYear(1404)).isFalse();
        assertThat(JalaliDate.isLeapYear(1405)).isFalse();
        assertThat(JalaliDate.lengthOfMonth(1403, 12)).isEqualTo(30);
        assertThat(JalaliDate.lengthOfMonth(1405, 12)).isEqualTo(29);
        assertThat(JalaliDate.lengthOfMonth(1405, 6)).isEqualTo(31);
        assertThat(JalaliDate.lengthOfMonth(1405, 7)).isEqualTo(30);
    }

    @Test
    void parsesPersianAndLatinDigits() {
        assertThat(JalaliDate.parse("۱۴۰۵/۰۷/۱۴")).isEqualTo(JalaliDate.of(1405, 7, 14));
        assertThat(JalaliDate.parse("1405-7-1")).isEqualTo(JalaliDate.of(1405, 7, 1));
        assertThat(JalaliDate.parse(" 1405.07.14 ")).isEqualTo(JalaliDate.of(1405, 7, 14));
        assertThatThrownBy(() -> JalaliDate.parse("1405/13/01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JalaliDate.parse("1405/12/30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JalaliDate.parse("hello")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsMonthsWithClamping() {
        assertThat(JalaliDate.of(1405, 6, 31).plusMonths(1)).isEqualTo(JalaliDate.of(1405, 7, 30));
        assertThat(JalaliDate.of(1405, 11, 30).plusMonths(1)).isEqualTo(JalaliDate.of(1405, 12, 29));
        assertThat(JalaliDate.of(1403, 11, 30).plusMonths(1)).isEqualTo(JalaliDate.of(1403, 12, 30));
        assertThat(JalaliDate.of(1405, 12, 15).plusMonths(1)).isEqualTo(JalaliDate.of(1406, 1, 15));
        assertThat(JalaliDate.of(1405, 1, 15).plusMonths(-1)).isEqualTo(JalaliDate.of(1404, 12, 15));
    }

    @Test
    void formatsForDisplay() {
        JalaliDate date = JalaliDate.of(1405, 7, 14);
        assertThat(date.toString()).isEqualTo("1405/07/14");
        assertThat(date.toDisplayString()).isEqualTo("۱۴ مهر ۱۴۰۵");
        assertThat(date.dayOfWeekName()).isEqualTo("سه‌شنبه");
    }

    @Test
    void monthBoundariesAndArithmetic() {
        JalaliMonth mehr = JalaliMonth.of(1405, 7);
        assertThat(mehr.startDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(mehr.endDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(mehr.contains(LocalDate.of(2026, 10, 6))).isTrue();
        assertThat(mehr.contains(LocalDate.of(2026, 10, 23))).isFalse();
        assertThat(mehr.plusMonths(6)).isEqualTo(JalaliMonth.of(1406, 1));
        assertThat(mehr.plusMonths(-7)).isEqualTo(JalaliMonth.of(1404, 12));
        assertThat(mehr.monthsUntil(JalaliMonth.of(1406, 7))).isEqualTo(12);
        assertThat(JalaliMonth.parse("۱۴۰۵-۰۷")).isEqualTo(mehr);
        assertThat(mehr.toString()).isEqualTo("1405-07");
        assertThat(mehr.label()).isEqualTo("مهر ۱۴۰۵");
        assertThat(JalaliMonth.from(LocalDate.of(2026, 3, 20))).isEqualTo(JalaliMonth.of(1404, 12));
    }
}
