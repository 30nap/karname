package ir.karname.category;

import ir.karname.category.CategoryService.CategoryRequest;
import ir.karname.category.CategoryService.CategoryView;
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
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categories;
    private final MerchantRuleService merchantRules;

    public CategoryController(CategoryService categories, MerchantRuleService merchantRules) {
        this.categories = categories;
        this.merchantRules = merchantRules;
    }

    public record SuggestionView(Long categoryId) {
    }

    @GetMapping
    public List<CategoryView> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return categories.list(user.id()).stream().map(CategoryView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody CategoryRequest request) {
        return CategoryView.of(categories.create(user.id(), request));
    }

    @PutMapping("/{id}")
    public CategoryView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestBody CategoryRequest request) {
        return CategoryView.of(categories.update(user.id(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id,
            @RequestParam(required = false) Long reassignTo) {
        categories.delete(user.id(), id, reassignTo);
    }

    @GetMapping("/suggest")
    public SuggestionView suggest(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam String description) {
        return new SuggestionView(merchantRules.suggest(user.id(), description).orElse(null));
    }
}
