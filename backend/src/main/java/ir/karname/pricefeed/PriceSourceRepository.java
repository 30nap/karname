package ir.karname.pricefeed;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PriceSourceRepository extends JpaRepository<PriceSource, Long> {

    List<PriceSource> findAllByOrderByPriorityAscIdAsc();

    List<PriceSource> findByEnabledTrueOrderByPriorityAscIdAsc();

    boolean existsByNameAndIdNot(String name, long id);

    boolean existsByName(String name);

    @Query("SELECT COALESCE(MAX(s.priority), 0) FROM PriceSource s")
    int maxPriority();
}
