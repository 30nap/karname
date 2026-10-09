package ir.karname.ai.tools;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CalculatorTest {

    private static String eval(String expression) {
        return Calculator.evaluate(expression).toPlainString();
    }

    @Test
    void evaluatesWithPrecedenceAndExactDecimals() {
        assertThat(eval("2 + 3 * 4")).isEqualTo("14");
        assertThat(eval("(2 + 3) * 4")).isEqualTo("20");
        assertThat(eval("0.1 + 0.2")).isEqualTo("0.3");
        assertThat(eval("2 ^ 3 ^ 2")).isEqualTo("512");
        assertThat(eval("-2 ^ 2")).isEqualTo("4");
        assertThat(eval("10 / 4")).isEqualTo("2.5");
        assertThat(eval("1 / 3")).isEqualTo("0.33333333");
        assertThat(eval("2 ^ -2")).isEqualTo("0.25");
        assertThat(eval("100 - -5")).isEqualTo("105");
    }

    @Test
    void readsPersianDigitsSeparatorsAndPercent() {
        assertThat(eval("۱۲٬۵۰۰٬۰۰۰ - 9,800,000")).isEqualTo("2700000");
        assertThat(eval("(12,500,000 - 9,800,000) / 12,500,000 * 100")).isEqualTo("21.6");
        assertThat(eval("25,000,000 * 15%")).isEqualTo("3750000");
        assertThat(eval("۳٫۵ × ۲ ÷ ۷")).isEqualTo("1");
        assertThat(Calculator.evaluate("1000000000 * 1000000")).isEqualByComparingTo(new BigDecimal("1E15"));
    }

    @Test
    void explainsBadExpressions() {
        assertThatThrownBy(() -> Calculator.evaluate("5 / (3 - 3)")).hasMessage("division by zero");
        assertThatThrownBy(() -> Calculator.evaluate("2 + x")).hasMessageContaining("unexpected 'x'");
        assertThatThrownBy(() -> Calculator.evaluate("(2 + 3")).hasMessage("missing ')'");
        assertThatThrownBy(() -> Calculator.evaluate("2 ^ 0.5")).hasMessageContaining("whole numbers");
        assertThatThrownBy(() -> Calculator.evaluate("1+".repeat(200) + "1")).hasMessageContaining("characters");
        assertThatThrownBy(() -> Calculator.evaluate("(".repeat(50) + "1" + ")".repeat(50))).hasMessageContaining("nested");
        assertThatThrownBy(() -> Calculator.evaluate("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesResultsTooFineToRoundCheaply() {
        // the scale of powers of powers explodes while the precision stays small
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> Calculator.evaluate("((0.1^1000)^1000)^1000")).hasMessageContaining("too large or too small"));
        // financial factors well inside the bounds still work
        assertThat(Calculator.evaluate("(1.02)^-120")).isEqualByComparingTo(new BigDecimal("0.09289223"));
    }
}
