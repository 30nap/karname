package ir.karname.loan;

import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.web.ApiException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Installment schedules in whole Toman. Interest is charged monthly at annual rate / 12 on the
 * remaining principal; the last installment absorbs rounding. Due dates keep the first due date's
 * Jalali day of month, clamped to shorter months (31 Shahrivar, 30 Mehr, ... 29 Esfand).
 */
final class LoanSchedule {

    private static final MathContext MC = new MathContext(34);
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    record Line(int number, LocalDate dueDate, BigDecimal amount, BigDecimal principal, BigDecimal interest, BigDecimal balanceAfter) {
    }

    private LoanSchedule() {
    }

    static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        return annualRatePercent.divide(TWELVE_HUNDRED, MC);
    }

    /** P·r·(1+r)^n / ((1+r)^n − 1), or P/n without interest; rounded up to a whole Toman. */
    static BigDecimal annuityPayment(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        BigDecimal r = monthlyRate(annualRatePercent);
        if (r.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 0, RoundingMode.CEILING);
        }
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        return principal.multiply(r).multiply(growth).divide(growth.subtract(BigDecimal.ONE), MC).setScale(0, RoundingMode.CEILING);
    }

    static List<Line> schedule(BigDecimal principal, BigDecimal annualRatePercent, int months, LoanMethod method,
            BigDecimal fixedInstallment, LocalDate firstDue) {
        BigDecimal r = monthlyRate(annualRatePercent);
        BigDecimal payment = fixedInstallment != null ? fixedInstallment
                : method == LoanMethod.ANNUITY ? annuityPayment(principal, annualRatePercent, months) : null;
        BigDecimal equalPrincipal = principal.divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP);
        JalaliDate first = JalaliDate.from(firstDue);
        List<Line> lines = new ArrayList<>(months);
        BigDecimal balance = principal;
        for (int k = 1; k <= months; k++) {
            BigDecimal interest = balance.multiply(r).setScale(0, RoundingMode.HALF_UP);
            BigDecimal principalPart;
            if (k == months) {
                principalPart = balance;
            } else if (payment != null) {
                principalPart = payment.subtract(interest);
                if (principalPart.signum() <= 0) {
                    throw ApiException.badRequest("loan.installmentTooSmall");
                }
                principalPart = principalPart.min(balance);
            } else {
                principalPart = equalPrincipal.min(balance);
            }
            balance = balance.subtract(principalPart);
            lines.add(new Line(k, first.plusMonths(k - 1).toGregorian(), principalPart.add(interest), principalPart, interest, balance));
        }
        return lines;
    }
}
