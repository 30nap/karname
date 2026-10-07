package ir.karname.ai.provider;

import ir.karname.ai.llm.fake.FakeLlm;

/**
 * Starting points for the provider form. Model names change too often to hard-code, so apart from
 * Claude's the model is picked from the provider's own list. Local servers start with tools and
 * JSON schemas off, which every model handles; an administrator can turn them on.
 */
public enum AiPreset {

    ANTHROPIC(AiProviderKind.ANTHROPIC, "Anthropic (Claude)", "https://api.anthropic.com", "claude-opus-5-5", true, true, true, true),
    OPENAI(AiProviderKind.OPENAI_COMPATIBLE, "OpenAI", "https://api.openai.com/v1", null, true, true, true, true),
    GEMINI(AiProviderKind.OPENAI_COMPATIBLE, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", null, true, true, true, true),
    DEEPSEEK(AiProviderKind.OPENAI_COMPATIBLE, "DeepSeek", "https://api.deepseek.com/v1", null, true, false, true, true),
    OPENROUTER(AiProviderKind.OPENAI_COMPATIBLE, "OpenRouter", "https://openrouter.ai/api/v1", null, true, true, true, true),
    GROQ(AiProviderKind.OPENAI_COMPATIBLE, "Groq", "https://api.groq.com/openai/v1", null, true, false, true, true),
    MISTRAL(AiProviderKind.OPENAI_COMPATIBLE, "Mistral", "https://api.mistral.ai/v1", null, true, false, false, true),
    XAI(AiProviderKind.OPENAI_COMPATIBLE, "xAI (Grok)", "https://api.x.ai/v1", null, true, true, true, true),
    OLLAMA(AiProviderKind.OPENAI_COMPATIBLE, "Ollama (روی سرور خودتان)", "http://localhost:11434/v1", null, false, false, true, false),
    LM_STUDIO(AiProviderKind.OPENAI_COMPATIBLE, "LM Studio (روی سرور خودتان)", "http://localhost:1234/v1", null, false, false, false, false),
    CUSTOM(AiProviderKind.OPENAI_COMPATIBLE, "سرویس دیگر سازگار با OpenAI", "", null, true, false, false, false),
    FAKE(AiProviderKind.FAKE, "مدل آزمایشی آفلاین", "", FakeLlm.MODEL, true, true, false, false);

    private final AiProviderKind kind;
    private final String label;
    private final String baseUrl;
    private final String defaultModel;
    private final boolean tools;
    private final boolean jsonSchema;
    private final boolean streamUsage;
    private final boolean needsKey;

    AiPreset(AiProviderKind kind, String label, String baseUrl, String defaultModel, boolean tools, boolean jsonSchema, boolean streamUsage,
            boolean needsKey) {
        this.kind = kind;
        this.label = label;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.tools = tools;
        this.jsonSchema = jsonSchema;
        this.streamUsage = streamUsage;
        this.needsKey = needsKey;
    }

    public AiProviderKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean tools() {
        return tools;
    }

    public boolean jsonSchema() {
        return jsonSchema;
    }

    public boolean streamUsage() {
        return streamUsage;
    }

    public boolean needsKey() {
        return needsKey;
    }
}
