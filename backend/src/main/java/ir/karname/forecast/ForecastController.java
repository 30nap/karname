package ir.karname.forecast;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.forecast.ForecastService.Forecast;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/forecast")
public class ForecastController {

    private final ForecastService forecasts;

    public ForecastController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping
    public Forecast forecast(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(defaultValue = "90") int days) {
        return forecasts.forecast(user.id(), days);
    }
}
