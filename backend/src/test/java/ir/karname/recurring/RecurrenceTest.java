package ir.karname.recurring;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceTest {

    @Test
    void monthlyOnTheThirtyFirstFallsOnTheLastDayOfShorterMonths() {
        // 31 Shahrivar 1405 = 2026-09-22
        Recurrence r = new Recurrence(Frequency.MONTHLY, 1, 31, null, null, LocalDate.of(2026, 9, 22), null);
        assertThat(r.between(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 4, 30))).containsExactly(
                LocalDate.of(2026, 9, 22),  // 31 Shahrivar
                LocalDate.of(2026, 10, 22), // 30 Mehr
                LocalDate.of(2026, 11, 21), // 30 Aban
                LocalDate.of(2026, 12, 21), // 30 Azar
                LocalDate.of(2027, 1, 20),  // 30 Dey
                LocalDate.of(2027, 2, 19),  // 30 Bahman
                LocalDate.of(2027, 3, 20),  // 29 Esfand
                LocalDate.of(2027, 4, 20)); // 31 Farvardin 1406
    }

    @Test
    void monthlyEveryThreeMonthsFromAMidpoint() {
        // the 1st of Farvardin, Tir, Mehr, Dey; asked from Shahrivar on
        Recurrence r = new Recurrence(Frequency.MONTHLY, 3, 1, null, null, LocalDate.of(2026, 3, 21), null);
        assertThat(r.between(LocalDate.of(2026, 8, 30), LocalDate.of(2027, 1, 30)))
                .containsExactly(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 12, 22));
    }

    @Test
    void weeklyOnFridaysUntilTheEndDate() {
        // Friday = 6; 2026-10-09 is a Friday
        Recurrence r = new Recurrence(Frequency.WEEKLY, 1, null, 6, null, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 30));
        assertThat(r.between(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
                .containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 30));
        assertThat(Recurrence.weekday(LocalDate.of(2026, 10, 10))).isZero(); // Saturday
    }

    @Test
    void everyOtherWeekSkipsAhead() {
        // 2026-01-03 plus multiples of 14 days: 39 weeks later is an off week
        Recurrence r = new Recurrence(Frequency.WEEKLY, 2, null, null, null, LocalDate.of(2026, 1, 3), null);
        assertThat(r.between(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .containsExactly(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 24));
    }

    @Test
    void yearlyOnALeapDay() {
        // 30 Esfand exists only in leap years (1403 yes, 1404 no -> 29 Esfand)
        Recurrence r = new Recurrence(Frequency.YEARLY, 1, 30, null, 12, LocalDate.of(2024, 3, 1), null);
        assertThat(r.between(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31)))
                .containsExactly(LocalDate.of(2025, 3, 20), LocalDate.of(2026, 3, 20));
    }
}
