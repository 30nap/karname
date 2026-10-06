package ir.karname.loan;

import ir.karname.common.web.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanScheduleTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal sum(List<LoanSchedule.Line> lines, java.util.function.Function<LoanSchedule.Line, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void annuityPaymentMatchesBankTables() {
        // 18% over 36 months: 36,152 per million
        assertThat(LoanSchedule.annuityPayment(d("100000000"), d("18"), 36)).isEqualByComparingTo("3615240");
        // Qard al-Hasan with a 4% fee over 5 years
        assertThat(LoanSchedule.annuityPayment(d("50000000"), d("4"), 60)).isEqualByComparingTo("920827");
        assertThat(LoanSchedule.annuityPayment(d("10000000"), BigDecimal.ZERO, 3)).isEqualByComparingTo("3333334");
    }

    @Test
    void annuityScheduleSplitsPrincipalAndInterestAndClosesTheBalance() {
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(d("100000000"), d("18"), 36, LoanMethod.ANNUITY, null, LocalDate.of(2026, 10, 6));
        assertThat(lines).hasSize(36);
        assertThat(lines.getFirst().interest()).isEqualByComparingTo("1500000");
        assertThat(lines.getFirst().principal()).isEqualByComparingTo("2115240");
        assertThat(lines.get(1).interest()).isEqualByComparingTo("1468271");
        assertThat(lines.getLast().amount()).isEqualByComparingTo("3615220");
        assertThat(lines.getLast().balanceAfter()).isZero();
        assertThat(sum(lines, LoanSchedule.Line::principal)).isEqualByComparingTo("100000000");
        assertThat(sum(lines, LoanSchedule.Line::interest)).isEqualByComparingTo("30148620");
    }

    @Test
    void equalPrincipalInstallmentsDecrease() {
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(d("12000000"), d("24"), 12, LoanMethod.EQUAL_PRINCIPAL, null, LocalDate.of(2026, 10, 6));
        assertThat(lines.getFirst().amount()).isEqualByComparingTo("1240000");
        assertThat(lines.get(1).amount()).isEqualByComparingTo("1220000");
        assertThat(lines.getLast().amount()).isEqualByComparingTo("1020000");
        assertThat(sum(lines, LoanSchedule.Line::interest)).isEqualByComparingTo("1560000");
    }

    @Test
    void fixedInstallmentFromTheBankIsUsedAsIs() {
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(d("100000000"), d("18"), 36, LoanMethod.ANNUITY, d("3616000"), LocalDate.of(2026, 10, 6));
        assertThat(lines.getFirst().amount()).isEqualByComparingTo("3616000");
        assertThat(lines.getLast().balanceAfter()).isZero();
        assertThatThrownBy(() -> LoanSchedule.schedule(d("100000000"), d("18"), 36, LoanMethod.ANNUITY, d("1000000"), LocalDate.of(2026, 10, 6)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void dueDatesKeepTheJalaliDayClampedToShorterMonths() {
        // 31 Shahrivar 1405, then 30 Mehr, 30 Aban, 30 Azar, 30 Dey, 30 Bahman and 29 Esfand
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(d("7000000"), BigDecimal.ZERO, 7, LoanMethod.ANNUITY, null, LocalDate.of(2026, 9, 22));
        assertThat(lines.stream().map(LoanSchedule.Line::dueDate).toList()).containsExactly(
                LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 21), LocalDate.of(2026, 12, 21),
                LocalDate.of(2027, 1, 20), LocalDate.of(2027, 2, 19), LocalDate.of(2027, 3, 20));
        assertThat(lines.get(6).dueDate()).isEqualTo(LocalDate.of(2027, 3, 20)); // 29 Esfand 1405
    }
}
