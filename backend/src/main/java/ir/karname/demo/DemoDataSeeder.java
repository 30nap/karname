package ir.karname.demo;

import ir.karname.ai.provider.AiTask;
import ir.karname.ai.provider.AiPreset;
import ir.karname.ai.provider.AiProviderService;
import ir.karname.ai.provider.AiProviderService.ProviderRequest;
import ir.karname.ai.provider.AiProviderService.ProviderView;
import ir.karname.ai.provider.AiProviderService.RouteRequest;
import ir.karname.common.config.KarnameProperties;
import ir.karname.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * With {@code karname.demo.enabled}, creates the user "demo" on the first start (never again
 * once it exists) and, when the offline model is enabled and no AI service is set up yet, routes
 * every AI task to it so the assistant works without any key.
 */
@Component
@ConditionalOnProperty(name = "karname.demo.enabled", havingValue = "true")
class DemoDataSeeder {

    static final String USERNAME = "demo";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoData demo;
    private final UserRepository users;
    private final AiProviderService providers;
    private final KarnameProperties properties;

    DemoDataSeeder(DemoData demo, UserRepository users, AiProviderService providers, KarnameProperties properties) {
        this.demo = demo;
        this.users = users;
        this.providers = providers;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (users.existsByUsername(USERNAME)) {
            return;
        }
        String configured = properties.demo().password();
        String password = StringUtils.hasText(configured) ? configured : randomPassword();
        demo.create(USERNAME, "کاربر نمایشی", password);
        if (StringUtils.hasText(configured)) {
            log.info("Demo user \"{}\" created with six months of sample data", USERNAME);
        } else {
            // sample data only; set KARNAME_DEMO_PASSWORD to choose the password instead
            log.warn("Demo user \"{}\" created with six months of sample data; password: {}", USERNAME, password);
        }
        if (properties.ai().fakeEnabled() && providers.list().isEmpty()) {
            ProviderView fake = providers.create(new ProviderRequest("مدل آزمایشی", AiPreset.FAKE, null, null, null, null, null, null,
                    null, null, null, null, null, true));
            providers.saveRoutes(Arrays.stream(AiTask.values()).map(task -> new RouteRequest(task, fake.id(), null, null)).toList());
            log.info("Offline demo model set up for every AI task");
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
