package ir.karname.common.jalali;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks the calendar against ICU (exported from Node's Intl API into
 * {@code jalali-icu-nowruz.csv}): the Gregorian date of each Nowruz and the length of Esfand
 * fully determine every date of a Jalali year.
 */
class JalaliIcuCrossCheckTest {

    @Test
    void matchesIcuForYears1300To1500() throws Exception {
        List<String> mismatches = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/jalali-icu-nowruz.csv")), StandardCharsets.UTF_8))) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                int year = Integer.parseInt(parts[0]);
                LocalDate nowruz = LocalDate.parse(parts[1]);
                int esfandDays = Integer.parseInt(parts[2]);
                rows++;
                if (!JalaliDate.of(year, 1, 1).toGregorian().equals(nowruz)
                        || JalaliDate.lengthOfMonth(year, 12) != esfandDays) {
                    mismatches.add(line + " vs " + JalaliDate.of(year, 1, 1).toGregorian() + "," + JalaliDate.lengthOfMonth(year, 12));
                }
            }
            assertThat(rows).isEqualTo(201);
        }
        assertThat(mismatches).isEmpty();
    }
}
