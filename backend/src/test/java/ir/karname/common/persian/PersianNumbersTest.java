package ir.karname.common.persian;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PersianNumbersTest {

    @Test
    void parsesNumbersWithSeparators() {
        assertThat(PersianNumbers.parseDecimal("1,250,000")).contains(new BigDecimal("1250000"));
        assertThat(PersianNumbers.parseDecimal("۱٬۲۵۰٬۰۰۰")).contains(new BigDecimal("1250000"));
        assertThat(PersianNumbers.parseDecimal("۱،۲۵۰")).contains(new BigDecimal("1250"));
        assertThat(PersianNumbers.parseDecimal("۲٫۵")).contains(new BigDecimal("2.5"));
        assertThat(PersianNumbers.parseDecimal("0.00012")).contains(new BigDecimal("0.00012"));
        assertThat(PersianNumbers.parseDecimal(" 1 250 000 ")).contains(new BigDecimal("1250000"));
        assertThat(PersianNumbers.parseDecimal("-500")).contains(new BigDecimal("-500"));
    }

    @Test
    void rejectsGarbage() {
        assertThat(PersianNumbers.parseDecimal("abc")).isEmpty();
        assertThat(PersianNumbers.parseDecimal("1.2.3")).isEmpty();
        assertThat(PersianNumbers.parseDecimal("")).isEmpty();
        assertThat(PersianNumbers.parseDecimal(null)).isEmpty();
    }
}
