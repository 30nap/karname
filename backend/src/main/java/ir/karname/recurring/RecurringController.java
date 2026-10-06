package ir.karname.recurring;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import ir.karname.recurring.RecurringService.Occurrence;
import ir.karname.recurring.RecurringService.PostRequest;
import ir.karname.recurring.RecurringService.RuleRequest;
import ir.karname.recurring.RecurringService.RuleView;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringService recurring;
    private final Clock clock;

    public RecurringController(RecurringService recurring, Clock clock) {
        this.recurring = recurring;
        this.clock = clock;
    }

    @GetMapping
    public List<RuleView> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return recurring.list(user.id());
    }

    @GetMapping("/{id}")
    public RuleView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return recurring.get(user.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody RuleRequest request) {
        return recurring.create(user.id(), request);
    }

    @PutMapping("/{id}")
    public RuleView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody RuleRequest request) {
        return recurring.update(user.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        recurring.delete(user.id(), id);
    }

    @GetMapping("/occurrences")
    public List<Occurrence> occurrences(@AuthenticationPrincipal KarnamePrincipal user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.minusDays(30);
        LocalDate end = to != null ? to : today.plusDays(60);
        if (end.isBefore(start) || start.plusDays(800).isBefore(end)) {
            throw ApiException.badRequest("recurring.invalidDates");
        }
        return recurring.occurrences(user.id(), start, end);
    }

    /** Due and upcoming reminders. */
    @GetMapping("/pending")
    public List<Occurrence> pending(@AuthenticationPrincipal KarnamePrincipal user) {
        return recurring.pending(user.id());
    }

    @PostMapping("/{id}/occurrences/{date}/post")
    public Map<String, Long> post(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestBody(required = false) PostRequest request) {
        return Map.of("transactionId", recurring.post(user.id(), id, date, request).getId());
    }

    @PostMapping("/{id}/occurrences/{date}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        recurring.skip(user.id(), id, date);
    }

    @DeleteMapping("/{id}/occurrences/{date}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unskip(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        recurring.unskip(user.id(), id, date);
    }
}
