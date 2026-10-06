package ir.karname.recurring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    List<RecurringRule> findByUserIdOrderByIdAsc(long userId);

    Optional<RecurringRule> findByIdAndUserId(long id, long userId);

    @Query("SELECT DISTINCT r.userId FROM RecurringRule r WHERE r.active = true AND r.mode = ir.karname.recurring.RecurringMode.AUTO")
    List<Long> findUsersWithAutoRules();
}
