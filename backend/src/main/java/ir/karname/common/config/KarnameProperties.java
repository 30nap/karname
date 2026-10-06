package ir.karname.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/** Application settings under the {@code karname.*} prefix. */
@ConfigurationProperties("karname")
public record KarnameProperties(
        @DefaultValue("Asia/Tehran") ZoneId timezone,
        @DefaultValue Security security) {

    public record Security(
            String secretKey,
            @DefaultValue("./data/secret.key") String secretKeyFile,
            @DefaultValue("30d") Duration rememberMeDuration,
            @DefaultValue("5") int loginMaxAttempts,
            @DefaultValue("15m") Duration loginLockout,
            @DefaultValue("false") boolean cookieSecure) {
    }
}
