package ir.karname.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** Rows that can affect {@code month}: those of that month and earlier ones (month keys sort as text). */
    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.month <= :month ORDER BY b.month")
    List<Budget> findUpTo(long userId, String month);

    Optional<Budget> findByUserIdAndCategoryIdAndMonth(long userId, long categoryId, String month);

    /** The latest recurring row before {@code month}: what the category inherits in that month. */
    Optional<Budget> findFirstByUserIdAndCategoryIdAndRecurringTrueAndMonthLessThanOrderByMonthDesc(long userId, long categoryId,
            String month);

    @Modifying
    @Query("DELETE FROM Budget b WHERE b.userId = :userId AND b.categoryId = :categoryId AND b.month >= :month")
    int deleteFrom(long userId, long categoryId, String month);
}
