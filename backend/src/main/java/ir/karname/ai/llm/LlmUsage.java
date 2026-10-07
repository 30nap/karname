package ir.karname.ai.llm;

/** Tokens a request consumed; cache reads and writes are counted apart from other input. */
public record LlmUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    public static final LlmUsage ZERO = new LlmUsage(0, 0, 0, 0);

    public LlmUsage plus(LlmUsage other) {
        if (other == null) {
            return this;
        }
        return new LlmUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
    }
}
