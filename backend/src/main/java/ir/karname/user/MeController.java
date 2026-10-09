package ir.karname.user;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.user.UserViews.MeView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in user's own profile, password, two-factor setup and account deletion. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;
    private final TwoFactorService twoFactor;
    private final SessionService sessions;

    public MeController(UserService userService, TwoFactorService twoFactor, SessionService sessions) {
        this.userService = userService;
        this.twoFactor = twoFactor;
        this.sessions = sessions;
    }

    public record ProfileRequest(@NotBlank @Size(max = 100) String displayName) {
    }

    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {
    }

    public record PasswordConfirmation(@NotBlank String password) {
    }

    public record TotpEnable(@NotBlank @Size(max = 16) String code, @NotBlank @Size(max = 200) String password) {
    }

    public record RecoveryCodesView(List<String> recoveryCodes) {
    }

    @GetMapping
    public MeView me(@AuthenticationPrincipal KarnamePrincipal principal) {
        return MeView.of(userService.get(principal.id()), userService.settings(principal.id()));
    }

    @PatchMapping
    public MeView updateProfile(@AuthenticationPrincipal KarnamePrincipal principal, @Valid @RequestBody ProfileRequest request) {
        User user = userService.updateProfile(principal.id(), request.displayName());
        return MeView.of(user, userService.settings(principal.id()));
    }

    /** Changes the password and signs out every other session of this user. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal KarnamePrincipal principal,
            @Valid @RequestBody PasswordChangeRequest request, HttpServletRequest httpRequest) {
        userService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        HttpSession session = httpRequest.getSession(false);
        sessions.invalidateAll(principal.username(), session != null ? session.getId() : null);
    }

    @PostMapping("/sessions/revoke-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOtherSessions(@AuthenticationPrincipal KarnamePrincipal principal, HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        sessions.invalidateAll(principal.username(), session != null ? session.getId() : null);
    }

    @PostMapping("/totp/setup")
    public TwoFactorService.SetupView setupTotp(@AuthenticationPrincipal KarnamePrincipal principal) {
        return twoFactor.setup(principal.id());
    }

    @PostMapping("/totp/enable")
    public RecoveryCodesView enableTotp(@AuthenticationPrincipal KarnamePrincipal principal, @Valid @RequestBody TotpEnable request) {
        return new RecoveryCodesView(twoFactor.enable(principal.id(), request.code(), request.password()));
    }

    @PostMapping("/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableTotp(@AuthenticationPrincipal KarnamePrincipal principal, @Valid @RequestBody PasswordConfirmation request) {
        twoFactor.disable(principal.id(), request.password());
    }

    /** Permanently deletes the account and all of its data. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal KarnamePrincipal principal,
            @Valid @RequestBody PasswordConfirmation request, HttpServletRequest httpRequest) {
        userService.verifyPassword(principal.id(), request.password());
        userService.delete(principal.id());
        sessions.invalidateAll(principal.username(), null);
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
