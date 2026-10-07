package ir.karname.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/** Application settings under the {@code karname.*} prefix. */
@ConfigurationProperties("karname")
public record KarnameProperties(
        @DefaultValue("Asia/Tehran") ZoneId timezone,
        @DefaultValue Security security,
        @DefaultValue Ai ai) {

    public record Security(
            String secretKey,
            @DefaultValue("./data/secret.key") String secretKeyFile,
            @DefaultValue("30d") Duration rememberMeDuration,
            @DefaultValue("5") int loginMaxAttempts,
            @DefaultValue("15m") Duration loginLockout,
            @DefaultValue("false") boolean cookieSecure) {
    }

    /**
     * @param anthropicApiKey when set, a provider using it is created on first start (the key itself
     *     is read from the environment, never stored)
     * @param defaultDailyLimit AI requests per user per day until an administrator sets a limit
     * @param fakeEnabled offers an offline scripted model, for tests and demos only
     */
    public record Ai(
            String anthropicApiKey,
            @DefaultValue("100") int defaultDailyLimit,
            @DefaultValue("false") boolean fakeEnabled) {
    }
}
