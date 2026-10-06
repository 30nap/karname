package ir.karname.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One scheduled installment; whether it is paid is read from the ledger (see {@link LoanService}). */
@Entity
@Table(name = "loan_installments")
public class LoanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(nullable = false)
    private int number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal principal;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal interest;

    protected LoanInstallment() {
    }

    public LoanInstallment(Long loanId, int number, LocalDate dueDate, BigDecimal amount, BigDecimal principal, BigDecimal interest) {
        this.loanId = loanId;
        this.number = number;
        this.dueDate = dueDate;
        this.amount = amount;
        this.principal = principal;
        this.interest = interest;
    }

    public Long getId() {
        return id;
    }

    public Long getLoanId() {
        return loanId;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getInterest() {
        return interest;
    }
}
