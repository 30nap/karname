package ir.karname.user.admin;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.security.Role;
import ir.karname.user.SessionService;
import ir.karname.user.SystemSettingsService;
import ir.karname.user.User;
import ir.karname.user.UserRepository;
import ir.karname.user.UserService;
import ir.karname.user.UserViews.AdminUserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Instance administration: users and registration policy. Restricted to ADMIN by SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final UserService userService;
    private final UserRepository users;
    private final SessionService sessions;
    private final SystemSettingsService systemSettings;

    public AdminUserController(UserService userService, UserRepository users, SessionService sessions,
            SystemSettingsService systemSettings) {
        this.userService = userService;
        this.users = users;
        this.sessions = sessions;
        this.systemSettings = systemSettings;
    }

    public record UserUpdateRequest(Boolean enabled, Role role) {
    }

    public record PasswordResetRequest(@NotBlank @Size(max = 128) String newPassword) {
    }

    public record SystemSettingsView(boolean registrationOpen) {
    }

    @GetMapping("/users")
    public List<AdminUserView> list() {
        return users.findAllByOrderByIdAsc().stream().map(AdminUserView::of).toList();
    }

    @PatchMapping("/users/{id}")
    public AdminUserView update(@AuthenticationPrincipal KarnamePrincipal admin, @PathVariable long id,
            @RequestBody UserUpdateRequest request) {
        User user = userService.updateByAdmin(admin.id(), id, request.enabled(), request.role());
        if (!user.isEnabled() || request.role() != null) {
            sessions.invalidateAll(user.getUsername(), null);
        }
        return AdminUserView.of(user);
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable long id, @Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(id, request.newPassword());
        sessions.invalidateAll(userService.get(id).getUsername(), null);
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal admin, @PathVariable long id) {
        if (id == admin.id()) {
            throw ir.karname.common.web.ApiException.conflict("auth.cannotDeleteSelf");
        }
        String username = userService.get(id).getUsername();
        userService.delete(id);
        sessions.invalidateAll(username, null);
    }

    @GetMapping("/system")
    public SystemSettingsView system() {
        return new SystemSettingsView(systemSettings.isRegistrationOpen());
    }

    @PutMapping("/system")
    public SystemSettingsView updateSystem(@RequestBody SystemSettingsView request) {
        systemSettings.setRegistrationOpen(request.registrationOpen());
        return system();
    }
}
