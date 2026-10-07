package ir.karname.ai.insight;

import ir.karname.ai.insight.CategorizeService.ApplyRequest;
import ir.karname.ai.insight.CategorizeService.ApplyResult;
import ir.karname.ai.insight.CategorizeService.Result;
import ir.karname.ai.insight.MonthlyReportService.ReportView;
import ir.karname.common.security.KarnamePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class InsightController {

    private final MonthlyReportService reports;
    private final CategorizeService categorize;

    public InsightController(MonthlyReportService reports, CategorizeService categorize) {
        this.reports = reports;
        this.categorize = categorize;
    }

    /** The stored report of a Jalali month ({@code 1405-07}), if any, and whether it is out of date. */
    @GetMapping("/reports/{month}")
    public ReportView report(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String month) {
        return reports.get(user.id(), month);
    }

    /** Writes (or rewrites) the report of a month. */
    @PostMapping("/reports/{month}")
    public ReportView generate(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String month) {
        return reports.generate(user.id(), month);
    }

    @PostMapping("/categorize")
    public Result categorize(@AuthenticationPrincipal KarnamePrincipal user) {
        return categorize.suggest(user.id());
    }

    @PostMapping("/categorize/apply")
    public ApplyResult apply(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody ApplyRequest request) {
        return categorize.apply(user.id(), request);
    }
}
