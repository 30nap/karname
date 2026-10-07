package ir.karname.ai.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    List<AiProvider> findAllByOrderByIdAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, long id);
}
