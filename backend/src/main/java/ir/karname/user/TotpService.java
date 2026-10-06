package ir.karname.user;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** RFC 6238 time-based one-time passwords (SHA-1, 6 digits, 30 s), compatible with authenticator apps. */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TotpService(Clock clock) {
        this.clock = clock;
    }

    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String otpauthUri(String secret, String username) {
        String label = URLEncoder.encode("Karname:" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Karname&algorithm=SHA1&digits=6&period=30";
    }

    /** Accepts the current code and one step before/after to tolerate clock drift. */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) {
            return false;
        }
        long step = clock.instant().getEpochSecond() / PERIOD_SECONDS;
        byte[] key = base32Decode(secret);
        for (long offset = -1; offset <= 1; offset++) {
            if (code(key, step + offset).equals(digits)) {
                return true;
            }
        }
        return false;
    }

    /** Current code for a secret (used by tests). */
    public String currentCode(String secret) {
        return code(base32Decode(secret), clock.instant().getEpochSecond() / PERIOD_SECONDS);
    }

    public List<String> generateRecoveryCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[5];
            random.nextBytes(bytes);
            String c = base32Encode(bytes).toLowerCase();
            codes.add(c.substring(0, 4) + "-" + c.substring(4, 8));
        }
        return codes;
    }

    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String text) {
        String s = text.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32 character: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }
}
