package ir.karname.ai.usage;

import ir.karname.ai.llm.LlmUsage;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.web.ApiException;
import ir.karname.user.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Accounting of AI use: one row per operation (a chat turn, an extraction, a report…) with its
 * tokens and estimated cost, and a daily per-user limit on operations.
 */
@Service
public class AiUsageService {

    static final String DAILY_LIMIT = "ai.dailyLimit";
    static final String TEST = "TEST";
    public static final int MAX_DAILY_LIMIT = 10_000;

    public enum Outcome {
        OK, ERROR, REFUSED, STOPPED
    }

    /** One operation, summed over the model calls it made. */
    public record Operation(Long userId, String task, Long providerId, String providerName, String model, int calls, LlmUsage usage,
            Outcome outcome) {
    }

    public record DaySummary(LocalDate day, String task, long operations, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheWriteTokens, BigDecimal costUsd, long failures) {
    }

    public record Quota(int limit, int used) {
    }

    private final JdbcClient jdbc;
    private final SystemSettingsService settings;
    private final KarnameProperties properties;
    private final Clock clock;

    public AiUsageService(JdbcClient jdbc, SystemSettingsService settings, KarnameProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    /** Recorded even when the surrounding work fails, so failed calls are accounted for too. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Operation op) {
        LlmUsage usage = op.usage() == null ? LlmUsage.ZERO : op.usage();
        jdbc.sql("""
                INSERT INTO ai_usage (user_id, task, provider_id, provider_name, model, calls, input_tokens, output_tokens,
                                      cache_read_tokens, cache_write_tokens, cost_usd, outcome, created_at)
                VALUES (:u, :task, :p, :pn, :m, :calls, :in, :out, :cr, :cw, :cost, :outcome, :at)
                """)
                .param("u", op.userId())
                .param("task", op.task())
                .param("p", op.providerId())
                .param("pn", op.providerName())
                .param("m", op.model())
                .param("calls", op.calls())
                .param("in", usage.inputTokens())
                .param("out", usage.outputTokens())
                .param("cr", usage.cacheReadTokens())
                .param("cw", usage.cacheWriteTokens())
                .param("cost", AiPricing.estimate(op.model(), usage))
                .param("outcome", op.outcome().name())
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Transactional(readOnly = true)
    public int dailyLimit() {
        return settings.get(DAILY_LIMIT).map(Integer::parseInt).orElse(properties.ai().defaultDailyLimit());
    }

    @Transactional
    public void setDailyLimit(int limit) {
        if (limit < 1 || limit > MAX_DAILY_LIMIT) {
            throw ApiException.badRequest("ai.invalidLimit", MAX_DAILY_LIMIT);
        }
        settings.set(DAILY_LIMIT, Integer.toString(limit));
    }

    @Transactional(readOnly = true)
    public Quota quota(long userId) {
        int used = jdbc.sql("SELECT count(*) FROM ai_usage WHERE user_id = :u AND task <> :test AND created_at >= :since")
                .param("u", userId)
                .param("test", TEST)
                .param("since", startOfToday().atOffset(ZoneOffset.UTC))
                .query(Integer.class).single();
        return new Quota(dailyLimit(), used);
    }

    /** @throws ApiException 429 when the user has used today's allowance */
    public void checkQuota(long userId) {
        Quota quota = quota(userId);
        if (quota.used() >= quota.limit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ai.quotaExceeded", quota.limit());
        }
    }

    /** Totals per Tehran day and task over the last {@code days} days, newest first. */
    @Transactional(readOnly = true)
    public List<DaySummary> summary(int days) {
        int span = Math.min(Math.max(days, 1), 366);
        return jdbc.sql("""
                SELECT (created_at AT TIME ZONE :zone)::date AS day, task, count(*) AS ops,
                       sum(input_tokens) AS input, sum(output_tokens) AS output, sum(cache_read_tokens) AS cread,
                       sum(cache_write_tokens) AS cwrite, sum(cost_usd) AS cost, count(*) FILTER (WHERE outcome <> 'OK') AS failures
                FROM ai_usage
                WHERE created_at >= :since
                GROUP BY 1, 2
                ORDER BY 1 DESC, 2
                """)
                .param("zone", properties.timezone().getId())
                .param("since", startOfToday().minusSeconds(86_400L * (span - 1)).atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new DaySummary(rs.getObject("day", LocalDate.class), rs.getString("task"), rs.getLong("ops"),
                        rs.getLong("input"), rs.getLong("output"), rs.getLong("cread"), rs.getLong("cwrite"), rs.getBigDecimal("cost"),
                        rs.getLong("failures")))
                .list();
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(properties.timezone())).atStartOfDay(properties.timezone()).toInstant();
    }
}
