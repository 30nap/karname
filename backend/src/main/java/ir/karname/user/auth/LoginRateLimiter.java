package ir.karname.user.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Locks out a username+IP pair after repeated failed logins, and throttles registrations per
 * IP. In-memory: Karname runs as a single instance.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_REGISTRATIONS_PER_HOUR = 10;

    private final int maxAttempts;
    private final Cache<String, AtomicInteger> failures;
    private final Cache<String, AtomicInteger> registrations;

    public LoginRateLimiter(KarnameProperties properties) {
        this.maxAttempts = properties.security().loginMaxAttempts();
        this.failures = Caffeine.newBuilder().expireAfterWrite(properties.security().loginLockout()).maximumSize(100_000).build();
        this.registrations = Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofHours(1)).maximumSize(100_000).build();
    }

    public void checkLogin(String username, String ip) {
        AtomicInteger count = failures.getIfPresent(key(username, ip));
        if (count != null && count.get() >= maxAttempts) {
            throw ApiException.tooManyRequests("auth.tooManyAttempts");
        }
    }

    public void recordFailure(String username, String ip) {
        failures.asMap().computeIfAbsent(key(username, ip), k -> new AtomicInteger()).incrementAndGet();
    }

    public void reset(String username, String ip) {
        failures.invalidate(key(username, ip));
    }

    public void checkRegistration(String ip) {
        AtomicInteger count = registrations.asMap().computeIfAbsent(ip, k -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_REGISTRATIONS_PER_HOUR) {
            throw ApiException.tooManyRequests("auth.tooManyRegistrations");
        }
    }

    /** Clears all counters (used by tests). */
    public void clear() {
        failures.invalidateAll();
        registrations.invalidateAll();
    }

    private static String key(String username, String ip) {
        return username + "|" + ip;
    }
}
