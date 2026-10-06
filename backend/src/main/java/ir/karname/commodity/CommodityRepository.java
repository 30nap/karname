package ir.karname.commodity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CommodityRepository extends JpaRepository<Commodity, Long> {

    /** Built-in commodities plus the user's own custom ones. */
    @Query("select c from Commodity c where c.userId is null or c.userId = :userId order by c.sortOrder, c.id")
    List<Commodity> findVisible(long userId);

    @Query("select c from Commodity c where c.code = :code and (c.userId is null or c.userId = :userId)")
    Optional<Commodity> findVisibleByCode(long userId, String code);

    @Query("select c from Commodity c where c.userId is null and c.code = :code")
    Optional<Commodity> findBuiltIn(String code);
}
