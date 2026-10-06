package ir.karname.common.crypto;

import ir.karname.common.config.KarnameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored in the database (LLM and price API keys, TOTP
 * secrets).
 *
 * <p>The key comes from {@code karname.security.secret-key} (Base64, 32 bytes). Without it, a
 * key file is used and created on first start, so a database dump alone never reveals the
 * secrets.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(KarnameProperties properties) {
        this.key = new SecretKeySpec(loadKey(properties.security()), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length);
            out.put(VERSION).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext));
            byte version = in.get();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported ciphertext version " + version);
            }
            byte[] iv = new byte[IV_LENGTH];
            in.get(iv);
            byte[] encrypted = new byte[in.remaining()];
            in.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (wrong secret key?)", e);
        }
    }

    private static byte[] loadKey(KarnameProperties.Security security) {
        if (StringUtils.hasText(security.secretKey())) {
            return checkLength(Base64.getDecoder().decode(security.secretKey().trim()));
        }
        Path file = Path.of(security.secretKeyFile());
        try {
            if (Files.exists(file)) {
                return checkLength(Base64.getDecoder().decode(Files.readString(file).trim()));
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            log.warn("No KARNAME_SECRET_KEY configured; generated a new key at {}. Back it up: without it, stored API keys cannot be decrypted.", file.toAbsolutePath());
            return generated;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create secret key file " + file, e);
        }
    }

    private static byte[] checkLength(byte[] key) {
        if (key.length != 32) {
            throw new IllegalStateException("karname.security.secret-key must be 32 bytes (Base64-encoded), got " + key.length);
        }
        return key;
    }
}
