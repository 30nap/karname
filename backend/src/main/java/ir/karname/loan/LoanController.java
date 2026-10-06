package ir.karname.loan;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.loan.LoanService.LoanRequest;
import ir.karname.loan.LoanService.LoanView;
import ir.karname.loan.LoanService.PaymentRequest;
import ir.karname.loan.LoanService.SchedulePreview;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    @GetMapping
    public List<LoanView> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return loans.list(user.id());
    }

    @GetMapping("/{id}")
    public LoanView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return loans.get(user.id(), id);
    }

    /** Installment amount and totals for the loan form, without saving anything. */
    @GetMapping("/preview")
    public SchedulePreview preview(@RequestParam BigDecimal principal, @RequestParam BigDecimal annualRate, @RequestParam int termMonths,
            @RequestParam(required = false) LoanMethod method, @RequestParam(required = false) BigDecimal installmentAmount,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firstDueDate) {
        return loans.preview(principal, annualRate, termMonths, method, installmentAmount, firstDueDate);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoanView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody LoanRequest request) {
        return loans.create(user.id(), request);
    }

    @PutMapping("/{id}")
    public LoanView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody LoanRequest request) {
        return loans.update(user.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean withAccount) {
        loans.delete(user.id(), id, withAccount);
    }

    @PostMapping("/{id}/installments/{number}/payment")
    public LoanView pay(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @PathVariable int number,
            @RequestBody PaymentRequest request) {
        return loans.pay(user.id(), id, number, request);
    }

    @DeleteMapping("/{id}/installments/{number}/payment")
    public LoanView unpay(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @PathVariable int number) {
        return loans.unpay(user.id(), id, number);
    }
}
