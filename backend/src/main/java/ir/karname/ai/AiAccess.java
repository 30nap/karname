package ir.karname.ai;

import ir.karname.ai.llm.LlmException;
import ir.karname.ai.provider.AiProviderService;
import ir.karname.ai.provider.AiProviderService.ResolvedRoute;
import ir.karname.ai.provider.AiTask;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.common.web.ApiException;
import ir.karname.user.UserService;
import ir.karname.user.UserSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Whether a user may use an AI task now: switched on in their settings, configured, within quota. */
@Component
public class AiAccess {

    private final AiProviderService providers;
    private final AiUsageService usage;
    private final UserService users;

    public AiAccess(AiProviderService providers, AiUsageService usage, UserService users) {
        this.providers = providers;
        this.usage = usage;
        this.users = users;
    }

    public ResolvedRoute require(long userId, AiTask task) {
        requireEnabled(userId);
        ResolvedRoute route = providers.resolve(task).orElseThrow(() -> ApiException.unavailable("ai.notConfigured"));
        usage.checkQuota(userId);
        return route;
    }

    public void requireEnabled(long userId) {
        if (!users.settings(userId).isAiEnabled()) {
            throw ApiException.forbidden("ai.disabled");
        }
    }

    /** Whether transaction descriptions and notes may be sent to the provider. */
    public boolean shareDescriptions(long userId) {
        UserSettings settings = users.settings(userId);
        return settings.isAiShareDescriptions();
    }

    /** A provider failure as an API error with the Persian explanation. */
    public static ApiException error(LlmException e) {
        HttpStatus status = switch (e.kind()) {
            case RATE_LIMIT, UNAVAILABLE, NETWORK -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CANCELLED -> HttpStatus.CONFLICT;
            case AUTH, BAD_REQUEST, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        return new ApiException(status, "ai.providerError", e.userMessage());
    }
}
