package ir.karname.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {

    List<LoanInstallment> findByLoanIdOrderByNumberAsc(long loanId);

    List<LoanInstallment> findByLoanIdInOrderByDueDateAsc(Collection<Long> loanIds);

    @Modifying
    @Query("DELETE FROM LoanInstallment i WHERE i.loanId = :loanId")
    void deleteByLoan(long loanId);
}
