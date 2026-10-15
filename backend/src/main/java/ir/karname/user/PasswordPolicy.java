package ir.karname.user;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

/** The byte limit of the existing BCrypt hashes, including passwords containing Persian text. */
public final class PasswordPolicy {

    private static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean withinEncodingLimit(String password) {
        return password != null && password.length() <= MAX_UTF8_BYTES
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    /** Invalid input is an ordinary failed password check, never an encoder exception. */
    public static boolean matches(PasswordEncoder encoder, String password, String hash) {
        return withinEncodingLimit(password) && encoder.matches(password, hash);
    }
}
