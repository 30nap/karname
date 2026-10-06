package ir.karname.account;

import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountViews.AccountView;
import ir.karname.common.security.KarnamePrincipal;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accounts;
    private final AccountViews views;

    public AccountController(AccountService accounts, AccountViews views) {
        this.accounts = accounts;
        this.views = views;
    }

    public record ArchiveRequest(boolean archived) {
    }

    @GetMapping
    public List<AccountView> list(@AuthenticationPrincipal KarnamePrincipal user,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return views.views(user.id(), accounts.list(user.id()).stream().filter(a -> includeArchived || !a.isArchived()).toList());
    }

    @GetMapping("/{id}")
    public AccountView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return views.view(user.id(), accounts.require(user.id(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody AccountRequest request) {
        return views.view(user.id(), accounts.create(user.id(), request));
    }

    @PutMapping("/{id}")
    public AccountView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestBody AccountRequest request) {
        return views.view(user.id(), accounts.update(user.id(), id, request));
    }

    @PostMapping("/{id}/archive")
    public AccountView archive(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestBody ArchiveRequest request) {
        return views.view(user.id(), accounts.setArchived(user.id(), id, request.archived()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean force) {
        accounts.delete(user.id(), id, force);
    }
}
