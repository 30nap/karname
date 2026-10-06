package ir.karname.pricefeed;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceSourceRepository extends JpaRepository<PriceSource, Long> {

    List<PriceSource> findAllByOrderByIdAsc();

    List<PriceSource> findByEnabledTrueOrderByIdAsc();

    boolean existsByNameAndIdNot(String name, long id);

    boolean existsByName(String name);
}
