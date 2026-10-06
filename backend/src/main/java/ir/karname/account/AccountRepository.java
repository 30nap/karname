package ir.karname.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderBySortOrderAscIdAsc(long userId);

    Optional<Account> findByIdAndUserId(long id, long userId);

    boolean existsByUserIdAndNameAndArchivedFalse(long userId, String name);
}
