package ir.karname.ai.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiRouteRepository extends JpaRepository<AiRoute, AiTask> {

    List<AiRoute> findByProviderId(long providerId);
}
