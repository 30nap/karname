package ir.karname.goal;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.goal.GoalService.GoalRequest;
import ir.karname.goal.GoalService.GoalView;
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
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService goals;

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    @GetMapping
    public List<GoalView> list(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return goals.list(user.id(), includeArchived);
    }

    @GetMapping("/{id}")
    public GoalView get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return goals.get(user.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody GoalRequest request) {
        return goals.create(user.id(), request);
    }

    @PutMapping("/{id}")
    public GoalView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody GoalRequest request) {
        return goals.update(user.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        goals.delete(user.id(), id);
    }
}
