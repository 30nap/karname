package ir.karname.ai.usage;

import ir.karname.ai.llm.LlmUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * List prices of Claude models in US dollars per million tokens, for cost estimates. Other
 * providers' prices vary by plan and change often, so their cost is reported as unknown.
 */
final class AiPricing {

    /** 5-minute cache writes cost a quarter more than plain input. */
    private static final BigDecimal CACHE_WRITE_FACTOR = new BigDecimal("1.25");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private record Price(String prefix, BigDecimal input, BigDecimal output, BigDecimal cacheRead) {
        Price(String prefix, String input, String output, String cacheRead) {
            this(prefix, new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead));
        }
    }

    // longest prefixes first, so "claude-opus-5-5" is not taken for "claude-opus-5"
    private static final List<Price> PRICES = List.of(
            new Price("claude-fable-5-1", "10", "50", "0.25"),
            new Price("claude-mythos-5-1", "10", "50", "0.25"),
            new Price("claude-fable-5", "10", "50", "1"),
            new Price("claude-mythos-5", "10", "50", "1"),
            new Price("claude-opus-5-5", "4", "20", "0.20"),
            new Price("claude-opus-5", "5", "25", "0.50"),
            new Price("claude-opus-4-8", "5", "25", "0.50"),
            new Price("claude-opus-4-7", "5", "25", "0.50"),
            new Price("claude-opus-4-6", "5", "25", "0.50"),
            new Price("claude-opus-4-5", "5", "25", "0.50"),
            new Price("claude-sonnet-5-5", "2", "10", "0.20"),
            new Price("claude-sonnet-5", "2", "10", "0.20"),
            new Price("claude-sonnet-4-6", "3", "15", "0.30"),
            new Price("claude-sonnet-4-5", "3", "15", "0.30"),
            new Price("claude-haiku-4-5", "1", "5", "0.10"));

    private AiPricing() {
    }

    /** Estimated cost in USD, or null when the model's price is not known. */
    static BigDecimal estimate(String model, LlmUsage usage) {
        if (model == null || usage == null) {
            return null;
        }
        String m = model.toLowerCase(Locale.ROOT);
        for (Price p : PRICES) {
            if (m.equals(p.prefix()) || m.startsWith(p.prefix() + "-") || m.startsWith(p.prefix() + "@")) {
                BigDecimal total = p.input().multiply(BigDecimal.valueOf(usage.inputTokens()))
                        .add(p.output().multiply(BigDecimal.valueOf(usage.outputTokens())))
                        .add(p.cacheRead().multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                        .add(p.input().multiply(CACHE_WRITE_FACTOR).multiply(BigDecimal.valueOf(usage.cacheWriteTokens())));
                return total.divide(MILLION, 6, RoundingMode.HALF_UP);
            }
        }
        return null;
    }
}
