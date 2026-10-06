package ir.karname.cheque;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChequeRepository extends JpaRepository<Cheque, Long> {

    List<Cheque> findByUserIdOrderByDueDateAscIdAsc(long userId);

    Optional<Cheque> findByIdAndUserId(long id, long userId);

    List<Cheque> findByUserIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc(long userId, ChequeStatus status, LocalDate until);
}
