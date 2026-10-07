package ir.karname.ai.provider;

import ir.karname.ai.llm.Effort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** The provider and model serving one {@link AiTask}. */
@Entity
@Table(name = "ai_routes")
public class AiRoute {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AiTask task;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false, length = 200)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Effort effort;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiRoute() {
    }

    public AiRoute(AiTask task) {
        this.task = task;
    }

    public AiTask getTask() {
        return task;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Effort getEffort() {
        return effort;
    }

    public void setEffort(Effort effort) {
        this.effort = effort;
    }
}
