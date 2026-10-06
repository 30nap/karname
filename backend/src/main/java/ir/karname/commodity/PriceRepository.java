package ir.karname.commodity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PriceRepository extends JpaRepository<Price, Long> {

    Optional<Price> findByIdAndUserId(long id, long userId);

    @Modifying
    @Query("delete from Price p where p.transactionId = :transactionId")
    void deleteByTransactionId(long transactionId);
}
