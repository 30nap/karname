package ir.karname.ai;

import ir.karname.ai.provider.AiProviderService;
import ir.karname.ai.provider.AiTask;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.ai.usage.AiUsageService.Quota;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.user.UserService;
import ir.karname.user.UserSettings;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** What AI features the user can use right now. */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiProviderService providers;
    private final AiUsageService usage;
    private final UserService users;

    public AiController(AiProviderService providers, AiUsageService usage, UserService users) {
        this.providers = providers;
        this.usage = usage;
        this.users = users;
    }

    /**
     * @param enabled the user's own switch
     * @param tasks which tasks have a provider configured
     */
    public record Status(boolean enabled, boolean shareDescriptions, Map<AiTask, Boolean> tasks, Quota quota) {
    }

    @GetMapping("/status")
    public Status status(@AuthenticationPrincipal KarnamePrincipal user) {
        UserSettings settings = users.settings(user.id());
        Map<AiTask, Boolean> tasks = new LinkedHashMap<>();
        Arrays.stream(AiTask.values()).forEach(task -> tasks.put(task, providers.resolve(task).isPresent()));
        return new Status(settings.isAiEnabled(), settings.isAiShareDescriptions(), tasks, usage.quota(user.id()));
    }
}
