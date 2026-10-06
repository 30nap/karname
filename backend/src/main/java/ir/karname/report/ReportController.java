package ir.karname.report;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.MonthParam;
import ir.karname.report.ReportService.Anomaly;
import ir.karname.report.ReportService.CategoryReport;
import ir.karname.report.ReportService.Kind;
import ir.karname.report.ReportService.MonthTotals;
import ir.karname.report.ReportService.TopItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reports;
    private final Clock clock;

    public ReportController(ReportService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @GetMapping("/monthly")
    public List<MonthTotals> monthly(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(defaultValue = "12") int months) {
        return reports.monthly(user.id(), months);
    }

    @GetMapping("/categories")
    public CategoryReport categories(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int span, @RequestParam(defaultValue = "EXPENSE") Kind kind) {
        return reports.categories(user.id(), MonthParam.parse(month, LocalDate.now(clock)), span, kind);
    }

    @GetMapping("/top")
    public List<TopItem> top(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int span, @RequestParam(defaultValue = "EXPENSE") Kind kind,
            @RequestParam(defaultValue = "10") int limit) {
        return reports.top(user.id(), MonthParam.parse(month, LocalDate.now(clock)), span, kind, limit);
    }

    @GetMapping("/anomalies")
    public List<Anomaly> anomalies(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(required = false) String month) {
        return reports.anomalies(user.id(), MonthParam.parse(month, LocalDate.now(clock)));
    }
}
