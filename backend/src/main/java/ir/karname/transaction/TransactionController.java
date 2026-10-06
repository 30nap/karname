package ir.karname.transaction;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.transaction.TransactionQueryService.Filter;
import ir.karname.transaction.TransactionQueryService.TransactionPage;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionViews.TransactionView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
public class TransactionController {

    private final TransactionService transactions;
    private final TransactionQueryService queries;
    private final TransactionViews views;

    public TransactionController(TransactionService transactions, TransactionQueryService queries, TransactionViews views) {
        this.transactions = transactions;
        this.queries = queries;
        this.views = views;
    }

    public record ReconcileRequest(BigDecimal actualBalance, LocalDate date) {
    }

    @GetMapping("/api/v1/transactions")
    public TransactionPage search(@AuthenticationPrincipal KarnamePrincipal user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<TransactionType> type,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) TransactionSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Filter filter = new Filter(from, to, type, accountId, categoryId, uncategorized, q, minAmount, maxAmount, source);
        return queries.search(user.id(), filter, page, size);
    }

    @GetMapping("/api/v1/transactions/{id}")
    public TransactionView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return views.view(user.id(), transactions.require(user.id(), id));
    }

    @PostMapping("/api/v1/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody TransactionRequest request) {
        return views.view(user.id(), transactions.create(user.id(), request));
    }

    @PutMapping("/api/v1/transactions/{id}")
    public TransactionView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestBody TransactionRequest request) {
        return views.view(user.id(), transactions.update(user.id(), id, request));
    }

    @DeleteMapping("/api/v1/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        transactions.delete(user.id(), id);
    }

    @PostMapping("/api/v1/accounts/{accountId}/reconcile")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView reconcile(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long accountId,
            @RequestBody ReconcileRequest request) {
        return views.view(user.id(), transactions.reconcile(user.id(), accountId, request.actualBalance(), request.date()));
    }
}
