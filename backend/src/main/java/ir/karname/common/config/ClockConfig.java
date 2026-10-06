package ir.karname.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Business time ("today", month boundaries) is always taken from this clock, in the
 * configured timezone (Asia/Tehran by default), so tests can pin it.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock(KarnameProperties properties) {
        return Clock.system(properties.timezone());
    }
}
