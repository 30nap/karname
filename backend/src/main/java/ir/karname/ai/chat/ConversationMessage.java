package ir.karname.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One message, never edited afterwards: replies are sent back to the model exactly as received.
 * A failed turn is only marked {@code excluded} (no longer sent) and keeps its {@code error}.
 */
@Entity
@Table(name = "ai_messages")
public class ConversationMessage {

    public enum Role {
        USER, ASSISTANT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private int turn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /** Provider-neutral parts, as JSON. */
    @Column(nullable = false)
    private String parts;

    @Column(name = "native_format", length = 16)
    private String nativeFormat;

    @Column(name = "native_json")
    private String nativeJson;

    @Column(nullable = false)
    private boolean excluded;

    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationMessage() {
    }

    public ConversationMessage(Long conversationId, int seq, int turn, Role role, String parts, String nativeFormat, String nativeJson) {
        this.conversationId = conversationId;
        this.seq = seq;
        this.turn = turn;
        this.role = role;
        this.parts = parts;
        this.nativeFormat = nativeFormat;
        this.nativeJson = nativeJson;
    }

    public Long getId() {
        return id;
    }

    public int getSeq() {
        return seq;
    }

    public int getTurn() {
        return turn;
    }

    public Role getRole() {
        return role;
    }

    public String getParts() {
        return parts;
    }

    public String getNativeFormat() {
        return nativeFormat;
    }

    public String getNativeJson() {
        return nativeJson;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
