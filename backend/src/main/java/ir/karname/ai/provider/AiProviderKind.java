package ir.karname.ai.provider;

public enum AiProviderKind {
    /** Claude through Anthropic's API (or a gateway speaking it). */
    ANTHROPIC,
    /** Anything speaking OpenAI's Chat Completions API. */
    OPENAI_COMPATIBLE,
    /** The offline scripted model; available only when enabled in the configuration. */
    FAKE
}
