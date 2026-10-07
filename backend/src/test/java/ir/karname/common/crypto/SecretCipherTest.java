package ir.karname.common.crypto;

import ir.karname.common.config.KarnameProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static KarnameProperties props(String key, Path file) {
        return new KarnameProperties(ZoneId.of("Asia/Tehran"),
                new KarnameProperties.Security(key, file.toString(), Duration.ofDays(30), 5, Duration.ofMinutes(15), false),
                new KarnameProperties.Ai(null, 100, false), new KarnameProperties.Demo(false, null));
    }

    private static String randomKey() {
        byte[] k = new byte[32];
        new java.security.SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripsAndUsesRandomIv(@TempDir Path dir) {
        SecretCipher cipher = new SecretCipher(props(randomKey(), dir.resolve("unused")));
        String a = cipher.encrypt("sk-ant-secret-کلید");
        String b = cipher.encrypt("sk-ant-secret-کلید");
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("sk-ant-secret-کلید");
        assertThat(cipher.decrypt(b)).isEqualTo("sk-ant-secret-کلید");
    }

    @Test
    void detectsTamperingAndWrongKey(@TempDir Path dir) {
        SecretCipher cipher = new SecretCipher(props(randomKey(), dir.resolve("unused")));
        String encrypted = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw))).isInstanceOf(IllegalStateException.class);

        SecretCipher other = new SecretCipher(props(randomKey(), dir.resolve("unused")));
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatesAndReusesKeyFile(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("data/secret.key");
        SecretCipher first = new SecretCipher(props(null, keyFile));
        assertThat(Files.exists(keyFile)).isTrue();
        String encrypted = first.encrypt("value");
        SecretCipher second = new SecretCipher(props("", keyFile));
        assertThat(second.decrypt(encrypted)).isEqualTo("value");
    }

    @Test
    void rejectsShortKeys(@TempDir Path dir) {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretCipher(props(shortKey, dir.resolve("x")))).isInstanceOf(IllegalStateException.class);
    }
}
