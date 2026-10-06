package ir.karname.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetServiceTest {

    @Test
    void roundsSuggestionsUpToTwoSignificantDigits() {
        assertThat(BudgetService.niceCeil(new BigDecimal("3456789"))).isEqualByComparingTo("3500000");
        assertThat(BudgetService.niceCeil(new BigDecimal("862000"))).isEqualByComparingTo("870000");
        assertThat(BudgetService.niceCeil(new BigDecimal("18000000"))).isEqualByComparingTo("18000000");
        assertThat(BudgetService.niceCeil(new BigDecimal("101"))).isEqualByComparingTo("110");
        assertThat(BudgetService.niceCeil(new BigDecimal("99.2"))).isEqualByComparingTo("100");
    }

    @Test
    void statusTurnsToWarningAtEightyPercentAndOverAboveTheBudget() {
        BigDecimal budget = new BigDecimal("1000");
        assertThat(BudgetService.status(new BigDecimal("799"), budget)).isEqualTo(BudgetService.Status.OK);
        assertThat(BudgetService.status(new BigDecimal("800"), budget)).isEqualTo(BudgetService.Status.WARNING);
        assertThat(BudgetService.status(new BigDecimal("1000"), budget)).isEqualTo(BudgetService.Status.WARNING);
        assertThat(BudgetService.status(new BigDecimal("1000.01"), budget)).isEqualTo(BudgetService.Status.OVER);
        assertThat(BudgetService.ratio(new BigDecimal("333"), budget)).isEqualByComparingTo("0.333");
    }
}
