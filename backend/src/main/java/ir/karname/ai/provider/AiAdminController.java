package ir.karname.ai.provider;

import ir.karname.ai.provider.AiProviderService.PresetsView;
import ir.karname.ai.provider.AiProviderService.ProviderRequest;
import ir.karname.ai.provider.AiProviderService.ProviderView;
import ir.karname.ai.provider.AiProviderService.RouteRequest;
import ir.karname.ai.provider.AiProviderService.RouteView;
import ir.karname.ai.provider.AiProviderService.TestRequest;
import ir.karname.ai.provider.AiProviderService.TestResult;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.ai.usage.AiUsageService.DaySummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AI providers, task routing, limits and usage. Restricted to ADMIN by SecurityConfig (/api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AiAdminController {

    private final AiProviderService providers;
    private final AiUsageService usage;

    public AiAdminController(AiProviderService providers, AiUsageService usage) {
        this.providers = providers;
        this.usage = usage;
    }

    public record Settings(int dailyLimit) {
    }

    @GetMapping("/presets")
    public PresetsView presets() {
        return providers.presets();
    }

    @GetMapping("/providers")
    public List<ProviderView> list() {
        return providers.list();
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderView create(@RequestBody ProviderRequest request) {
        return providers.create(request);
    }

    @PutMapping("/providers/{id}")
    public ProviderView update(@PathVariable long id, @RequestBody ProviderRequest request) {
        return providers.update(id, request);
    }

    @DeleteMapping("/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        providers.delete(id);
    }

    /** Models of a provider as configured in the form; {@code id} lends stored secrets. */
    @PostMapping("/providers/models")
    public List<String> models(@RequestParam(required = false) Long id, @RequestBody ProviderRequest request) {
        return providers.models(id, request);
    }

    @PostMapping("/providers/test")
    public TestResult test(@RequestParam(required = false) Long id, @RequestBody TestRequest request) {
        return providers.test(id, request);
    }

    @GetMapping("/routes")
    public List<RouteView> routes() {
        return providers.routes();
    }

    @PutMapping("/routes")
    public List<RouteView> saveRoutes(@RequestBody List<RouteRequest> routes) {
        return providers.saveRoutes(routes);
    }

    @GetMapping("/settings")
    public Settings settings() {
        return new Settings(usage.dailyLimit());
    }

    @PutMapping("/settings")
    public Settings saveSettings(@RequestBody Settings settings) {
        usage.setDailyLimit(settings == null ? 0 : settings.dailyLimit());
        return settings();
    }

    @GetMapping("/usage")
    public List<DaySummary> usage(@RequestParam(defaultValue = "30") int days) {
        return usage.summary(days);
    }
}
