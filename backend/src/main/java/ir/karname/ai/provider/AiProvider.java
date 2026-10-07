package ir.karname.ai.provider;

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

/** An AI provider configured by the administrator; secrets are stored encrypted. */
@Entity
@Table(name = "ai_providers")
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiProviderKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiPreset preset;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_key_enc")
    private String apiKeyEncrypted;

    /** The key is read from the ANTHROPIC_API_KEY environment variable instead of being stored. */
    @Column(name = "key_from_env", nullable = false)
    private boolean keyFromEnv;

    @Column(name = "headers_enc")
    private String headersEncrypted;

    /** JSON object of query parameters (not secret). */
    @Column(name = "query_params")
    private String queryParams;

    @Column(name = "default_model", length = 200)
    private String defaultModel;

    @Column(name = "supports_tools", nullable = false)
    private boolean supportsTools = true;

    @Column(name = "supports_json_schema", nullable = false)
    private boolean supportsJsonSchema;

    @Column(name = "stream_usage", nullable = false)
    private boolean streamUsage = true;

    @Column(name = "refusal_fallback", nullable = false)
    private boolean refusalFallback = true;

    @Column(nullable = false)
    private boolean enabled = true;

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

    public AiProviderKind getKind() {
        return kind;
    }

    public void setKind(AiProviderKind kind) {
        this.kind = kind;
    }

    public AiPreset getPreset() {
        return preset;
    }

    public void setPreset(AiPreset preset) {
        this.preset = preset;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public boolean isKeyFromEnv() {
        return keyFromEnv;
    }

    public void setKeyFromEnv(boolean keyFromEnv) {
        this.keyFromEnv = keyFromEnv;
    }

    public String getHeadersEncrypted() {
        return headersEncrypted;
    }

    public void setHeadersEncrypted(String headersEncrypted) {
        this.headersEncrypted = headersEncrypted;
    }

    public String getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(String queryParams) {
        this.queryParams = queryParams;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public boolean isSupportsTools() {
        return supportsTools;
    }

    public void setSupportsTools(boolean supportsTools) {
        this.supportsTools = supportsTools;
    }

    public boolean isSupportsJsonSchema() {
        return supportsJsonSchema;
    }

    public void setSupportsJsonSchema(boolean supportsJsonSchema) {
        this.supportsJsonSchema = supportsJsonSchema;
    }

    public boolean isStreamUsage() {
        return streamUsage;
    }

    public void setStreamUsage(boolean streamUsage) {
        this.streamUsage = streamUsage;
    }

    public boolean isRefusalFallback() {
        return refusalFallback;
    }

    public void setRefusalFallback(boolean refusalFallback) {
        this.refusalFallback = refusalFallback;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getVersion() {
        return version;
    }
}
