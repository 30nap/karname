package ir.karname.user;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    // RFC 6238 Appendix B SHA-1 secret
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        // 8-digit RFC values truncated to 6 digits (same dynamic truncation, modulo 10^6)
        assertThat(TotpService.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(TotpService.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(TotpService.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(TotpService.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTrips() {
        String encoded = TotpService.base32Encode(RFC_SECRET);
        assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(TotpService.base32Decode(encoded)).isEqualTo(RFC_SECRET);
    }

    @Test
    void verifiesWithDriftWindow() {
        Instant now = Instant.parse("2026-10-06T08:00:00Z");
        TotpService service = new TotpService(Clock.fixed(now, ZoneOffset.UTC));
        String secret = service.generateSecret();
        String current = service.currentCode(secret);
        assertThat(service.verify(secret, current)).isTrue();
        assertThat(service.verify(secret, current.substring(0, 3) + " " + current.substring(3))).isTrue();

        TotpService later = new TotpService(Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC));
        assertThat(later.verify(secret, current)).isTrue();
        TotpService muchLater = new TotpService(Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC));
        assertThat(muchLater.verify(secret, current)).isFalse();
        assertThat(service.verify(secret, "12345")).isFalse();
    }

    @Test
    void buildsOtpauthUri() {
        TotpService service = new TotpService(Clock.systemUTC());
        assertThat(service.otpauthUri("ABC", "sina"))
                .isEqualTo("otpauth://totp/Karname%3Asina?secret=ABC&issuer=Karname&algorithm=SHA1&digits=6&period=30");
        assertThat(service.generateRecoveryCodes(8)).hasSize(8).allMatch(c -> c.matches("[a-z2-7]{4}-[a-z2-7]{4}"));
    }
}
