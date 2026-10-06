package ir.karname.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndUserId(long id, long userId);

    boolean existsByUserIdAndExternalRef(long userId, String externalRef);

    Optional<Transaction> findByUserIdAndExternalRef(long userId, String externalRef);

    /** Transactions created for one source record, e.g. every payment of a loan ("loan:12:"). */
    List<Transaction> findByUserIdAndExternalRefStartingWith(long userId, String prefix);
}
