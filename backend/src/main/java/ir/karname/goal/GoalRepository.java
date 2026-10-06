package ir.karname.goal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findByUserIdOrderBySortOrderAscIdAsc(long userId);

    Optional<Goal> findByIdAndUserId(long id, long userId);

    long countByUserId(long userId);
}
