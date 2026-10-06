package ir.karname.user;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.user.UserViews.SettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final UserService userService;

    public SettingsController(UserService userService) {
        this.userService = userService;
    }

    public record SettingsRequest(
            @NotNull DisplayUnit displayUnit,
            @NotNull DigitStyle digitStyle,
            @NotNull ThemePreference theme,
            @NotNull @Size(max = 4) List<@Size(min = 1, max = 32) String> wealthUnits,
            @DecimalMin("0") @DecimalMax("1000") BigDecimal inflationRate,
            boolean aiEnabled,
            boolean aiShareDescriptions) {
    }

    @GetMapping
    public SettingsView get(@AuthenticationPrincipal KarnamePrincipal principal) {
        return SettingsView.of(userService.settings(principal.id()));
    }

    @PutMapping
    @Transactional
    public SettingsView update(@AuthenticationPrincipal KarnamePrincipal principal, @Valid @RequestBody SettingsRequest request) {
        UserSettings settings = userService.settings(principal.id());
        settings.setDisplayUnit(request.displayUnit());
        settings.setDigitStyle(request.digitStyle());
        settings.setTheme(request.theme());
        settings.setWealthUnits(request.wealthUnits().stream().map(String::trim).map(String::toUpperCase).distinct().toList());
        settings.setInflationRate(request.inflationRate());
        settings.setAiEnabled(request.aiEnabled());
        settings.setAiShareDescriptions(request.aiShareDescriptions());
        return SettingsView.of(settings);
    }
}
