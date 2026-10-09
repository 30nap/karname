package ir.karname.user.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles password checks: a username+address pair is locked after {@code loginMaxAttempts}
 * failures, and a username after a larger number from any addresses (guesses spread over many
 * addresses, or one front proxy shared by everyone). Registrations are throttled per address.
 * In-memory: Karname runs as a single instance.
 */
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final int maxAttemptsPerUsername;
    private final int maxRegistrationsPerHour;
    private final Cache<String, AtomicInteger> failures;
    private final Cache<String, AtomicInteger> usernameFailures;
    private final Cache<String, AtomicInteger> registrations;

    public LoginRateLimiter(KarnameProperties properties) {
        this.maxAttempts = properties.security().loginMaxAttempts();
        this.maxAttemptsPerUsername = Math.max(20, maxAttempts * 4);
        this.maxRegistrationsPerHour = properties.security().registrationsPerHour();
        this.failures = Caffeine.newBuilder().expireAfterWrite(properties.security().loginLockout()).maximumSize(100_000).build();
        this.usernameFailures = Caffeine.newBuilder().expireAfterWrite(properties.security().loginLockout()).maximumSize(100_000).build();
        this.registrations = Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofHours(1)).maximumSize(100_000).build();
    }

    public void checkLogin(String username, String ip) {
        AtomicInteger count = failures.getIfPresent(key(username, ip));
        AtomicInteger total = usernameFailures.getIfPresent(username);
        if (count != null && count.get() >= maxAttempts || total != null && total.get() >= maxAttemptsPerUsername) {
            throw ApiException.tooManyRequests("auth.tooManyAttempts");
        }
    }

    public void recordFailure(String username, String ip) {
        failures.asMap().computeIfAbsent(key(username, ip), k -> new AtomicInteger()).incrementAndGet();
        usernameFailures.asMap().computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
    }

    /** After a successful sign-in; the per-username count stays until it expires, so guessing elsewhere is not reset. */
    public void reset(String username, String ip) {
        failures.invalidate(key(username, ip));
    }

    public void checkRegistration(String ip) {
        AtomicInteger count = registrations.asMap().computeIfAbsent(ip, k -> new AtomicInteger());
        if (count.incrementAndGet() > maxRegistrationsPerHour) {
            throw ApiException.tooManyRequests("auth.tooManyRegistrations");
        }
    }

    /** Clears all counters (used by tests). */
    public void clear() {
        failures.invalidateAll();
        usernameFailures.invalidateAll();
        registrations.invalidateAll();
    }

    private static String key(String username, String ip) {
        return username + "|" + ip;
    }
}
