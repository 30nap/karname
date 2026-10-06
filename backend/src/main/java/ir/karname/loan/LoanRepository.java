package ir.karname.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByUserIdOrderByIdAsc(long userId);

    Optional<Loan> findByIdAndUserId(long id, long userId);

    Optional<Loan> findByAccountId(long accountId);
}
