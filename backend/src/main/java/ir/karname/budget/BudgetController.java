package ir.karname.budget;

import ir.karname.budget.BudgetService.BudgetMonth;
import ir.karname.budget.BudgetService.BudgetRequest;
import ir.karname.budget.BudgetService.RemoveScope;
import ir.karname.budget.BudgetService.Suggestion;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.MonthParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgets;
    private final Clock clock;

    public BudgetController(BudgetService budgets, Clock clock) {
        this.budgets = budgets;
        this.clock = clock;
    }

    @GetMapping
    public BudgetMonth month(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(required = false) String month) {
        return budgets.month(user.id(), month(month));
    }

    @PutMapping("/{month}/{categoryId}")
    public BudgetMonth set(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String month, @PathVariable long categoryId,
            @RequestBody BudgetRequest request) {
        JalaliMonth m = month(month);
        budgets.set(user.id(), m, categoryId, request);
        return budgets.month(user.id(), m);
    }

    @DeleteMapping("/{month}/{categoryId}")
    public BudgetMonth remove(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String month, @PathVariable long categoryId,
            @RequestParam(defaultValue = "FORWARD") RemoveScope scope) {
        JalaliMonth m = month(month);
        budgets.remove(user.id(), m, categoryId, scope);
        return budgets.month(user.id(), m);
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(required = false) String month) {
        return budgets.suggestions(user.id(), month(month));
    }

    private JalaliMonth month(String text) {
        return MonthParam.parse(text, LocalDate.now(clock));
    }
}
