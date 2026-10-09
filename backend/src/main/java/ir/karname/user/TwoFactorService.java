package ir.karname.user;

import ir.karname.common.crypto.SecretCipher;
import ir.karname.common.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/** Optional TOTP second factor with single-use recovery codes. */
@Service
public class TwoFactorService {

    private static final int RECOVERY_CODE_COUNT = 8;

    private final UserService userService;
    private final TotpService totp;
    private final SecretCipher cipher;
    private final PasswordEncoder passwordEncoder;

    public TwoFactorService(UserService userService, TotpService totp, SecretCipher cipher, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.totp = totp;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
    }

    public record SetupView(String secret, String otpauthUri) {
    }

    @Transactional
    public SetupView setup(long userId) {
        User user = userService.get(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("totp.alreadyEnabled");
        }
        String secret = totp.generateSecret();
        user.setTotpSecret(cipher.encrypt(secret));
        return new SetupView(secret, totp.otpauthUri(secret, user.getUsername()));
    }

    /**
     * Turns two-factor sign-in on. The password is asked again: a session left open somewhere must
     * not be enough to tie the account to someone else's authenticator.
     */
    @Transactional
    public List<String> enable(long userId, String code, String password) {
        User user = userService.get(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("totp.alreadyEnabled");
        }
        if (user.getTotpSecret() == null) {
            throw ApiException.badRequest("totp.notSetUp");
        }
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("auth.wrongPassword");
        }
        OptionalLong step = totp.matchingStep(cipher.decrypt(user.getTotpSecret()), code);
        if (step.isEmpty()) {
            throw ApiException.badRequest("totp.invalidCode");
        }
        user.setTotpLastStep(step.getAsLong());
        List<String> codes = totp.generateRecoveryCodes(RECOVERY_CODE_COUNT);
        user.setTotpRecoveryCodes(String.join(",", codes.stream().map(passwordEncoder::encode).toList()));
        user.setTotpEnabled(true);
        return codes;
    }

    @Transactional
    public void disable(long userId, String password) {
        User user = userService.get(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("auth.wrongPassword");
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpRecoveryCodes(null);
        user.setTotpLastStep(null);
    }

    /**
     * Verifies a login code: a current TOTP code not used before (an observed code cannot be replayed
     * within its validity window), or an unused recovery code (which is then consumed).
     */
    @Transactional
    public boolean verifyLogin(long userId, String code) {
        User user = userService.get(userId);
        if (!user.isTotpEnabled() || code == null || code.isBlank()) {
            return false;
        }
        OptionalLong step = totp.matchingStep(cipher.decrypt(user.getTotpSecret()), code);
        if (step.isPresent()) {
            if (user.getTotpLastStep() != null && step.getAsLong() <= user.getTotpLastStep()) {
                return false;
            }
            user.setTotpLastStep(step.getAsLong());
            return true;
        }
        String normalized = code.trim().toLowerCase();
        if (user.getTotpRecoveryCodes() == null) {
            return false;
        }
        List<String> hashes = new ArrayList<>(Arrays.asList(user.getTotpRecoveryCodes().split(",")));
        for (int i = 0; i < hashes.size(); i++) {
            if (passwordEncoder.matches(normalized, hashes.get(i))) {
                hashes.remove(i);
                user.setTotpRecoveryCodes(hashes.isEmpty() ? null : String.join(",", hashes));
                return true;
            }
        }
        return false;
    }
}
