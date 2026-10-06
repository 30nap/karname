package ir.karname.ledger;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.ledger.DashboardService.DashboardView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    public DashboardView dashboard(@AuthenticationPrincipal KarnamePrincipal user) {
        return dashboard.dashboard(user.id());
    }
}
