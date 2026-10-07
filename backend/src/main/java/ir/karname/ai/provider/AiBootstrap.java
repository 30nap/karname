package ir.karname.ai.provider;

import ir.karname.common.config.KarnameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * With an Anthropic key in the environment and no provider yet, sets up Claude for every task on
 * first start, so a Docker deployment works without visiting the settings. The key stays in the
 * environment; the provider only refers to it.
 */
@Component
class AiBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBootstrap.class);

    private final AiProviderRepository providers;
    private final AiRouteRepository routes;
    private final KarnameProperties properties;

    AiBootstrap(AiProviderRepository providers, AiRouteRepository routes, KarnameProperties properties) {
        this.providers = providers;
        this.routes = routes;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.ai().anthropicApiKey()) || providers.count() > 0) {
            return;
        }
        AiPreset preset = AiPreset.ANTHROPIC;
        AiProvider provider = new AiProvider();
        provider.setName("Anthropic");
        provider.setPreset(preset);
        provider.setKind(preset.kind());
        provider.setBaseUrl(preset.baseUrl());
        provider.setKeyFromEnv(true);
        provider.setDefaultModel(preset.defaultModel());
        provider.setSupportsTools(preset.tools());
        provider.setSupportsJsonSchema(preset.jsonSchema());
        provider.setStreamUsage(preset.streamUsage());
        AiProvider saved = providers.saveAndFlush(provider);
        for (AiTask task : AiTask.values()) {
            AiRoute route = new AiRoute(task);
            route.setProviderId(saved.getId());
            route.setModel(preset.defaultModel());
            route.setEffort(task.defaultEffort());
            routes.save(route);
        }
        log.info("Set up the Anthropic provider from ANTHROPIC_API_KEY for all AI tasks (model {})", preset.defaultModel());
    }
}
