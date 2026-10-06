package ir.karname.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderByKindAscSortOrderAscIdAsc(long userId);

    Optional<Category> findByIdAndUserId(long id, long userId);

    Optional<Category> findByUserIdAndSystemKey(long userId, String systemKey);

    List<Category> findByUserIdAndParentId(long userId, Long parentId);

    long countByUserId(long userId);
}
