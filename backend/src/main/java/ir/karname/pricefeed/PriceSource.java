package ir.karname.pricefeed;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "price_sources")
public class PriceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PriceSourceKind kind;

    @Column(length = 500)
    private String url;

    @Column(name = "headers_enc")
    private String headersEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PriceUnit unit = PriceUnit.TOMAN;

    /** JSON array of {@link PriceMapping}. */
    @Column(nullable = false)
    private String mappings = "[]";

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = 30;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "last_count")
    private Integer lastCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PriceSourceKind getKind() {
        return kind;
    }

    public void setKind(PriceSourceKind kind) {
        this.kind = kind;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHeadersEncrypted() {
        return headersEncrypted;
    }

    public void setHeadersEncrypted(String headersEncrypted) {
        this.headersEncrypted = headersEncrypted;
    }

    public PriceUnit getUnit() {
        return unit;
    }

    public void setUnit(PriceUnit unit) {
        this.unit = unit;
    }

    public String getMappings() {
        return mappings;
    }

    public void setMappings(String mappings) {
        this.mappings = mappings;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Integer getLastCount() {
        return lastCount;
    }

    /**
     * Records the outcome of a run: {@code count} prices read and the errors, if any. A run that
     * read at least one price is a success even when some mappings failed; a failed one keeps the
     * time of the last success.
     */
    public void recordRun(Instant at, int count, String error) {
        this.lastRunAt = at;
        this.lastCount = count;
        this.lastError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        if (count > 0) {
            this.lastSuccessAt = at;
        }
    }
}
