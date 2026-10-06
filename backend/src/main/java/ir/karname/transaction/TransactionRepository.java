package ir.karname.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndUserId(long id, long userId);

    boolean existsByUserIdAndExternalRef(long userId, String externalRef);
}
