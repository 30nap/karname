package ir.karname.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneId;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    /** Tuesday 14 Mehr 1405, 12:00 in Tehran. */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-10-06T08:30:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(DEFAULT_NOW, ZoneId.of("Asia/Tehran"));
    }
}
