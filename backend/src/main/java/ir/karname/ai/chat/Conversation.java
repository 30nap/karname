package ir.karname.ai.chat;

import ir.karname.ai.provider.AiProviderKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A conversation with the assistant, bound to the provider and model it started on. */
@Entity
@Table(name = "ai_conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "provider_id")
    private Long providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_kind", nullable = false, length = 24)
    private AiProviderKind providerKind;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(nullable = false)
    private int turns;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Conversation() {
    }

    public Conversation(Long userId, String title, Long providerId, AiProviderKind providerKind, String model, Instant now) {
        this.userId = userId;
        this.title = title;
        this.providerId = providerId;
        this.providerKind = providerKind;
        this.model = model;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getProviderId() {
        return providerId;
    }

    public AiProviderKind getProviderKind() {
        return providerKind;
    }

    public String getModel() {
        return model;
    }

    public int getTurns() {
        return turns;
    }

    /** Starts the next turn, returning its number. */
    public int nextTurn(Instant now) {
        updatedAt = now;
        return ++turns;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
