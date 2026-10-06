package ir.karname.cheque;

import ir.karname.cheque.ChequeService.ChequeRequest;
import ir.karname.cheque.ChequeService.ChequeView;
import ir.karname.cheque.ChequeService.StatusRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cheques")
public class ChequeController {

    private final ChequeService cheques;

    public ChequeController(ChequeService cheques) {
        this.cheques = cheques;
    }

    @GetMapping
    public List<ChequeView> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return cheques.list(user.id());
    }

    @GetMapping("/{id}")
    public ChequeView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return cheques.get(user.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChequeView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody ChequeRequest request) {
        return cheques.create(user.id(), request);
    }

    @PutMapping("/{id}")
    public ChequeView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody ChequeRequest request) {
        return cheques.update(user.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        cheques.delete(user.id(), id);
    }

    @PostMapping("/{id}/status")
    public ChequeView status(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody StatusRequest request) {
        return cheques.changeStatus(user.id(), id, request);
    }
}
