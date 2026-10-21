package ir.karname.ai.propose;

import ir.karname.ai.llm.JsonSchema;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.category.CategoryService.CategoryRequest;
import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** An income or expense category, top-level or under one. */
@Component
class CategoryProposals implements ProposalKind<CategoryRequest> {

    private final CategoryService categories;

    CategoryProposals(CategoryService categories) {
        this.categories = categories;
    }

    @Override
    public String kind() {
        return "CATEGORY";
    }

    @Override
    public String tool() {
        return "propose_category";
    }

    @Override
    public String label() {
        return "پیشنهاد دسته";
    }

    @Override
    public String description() {
        return "Proposes a new income or expense category for the user to confirm, at the top level or under an existing top-level "
                + "category of the same kind (two levels at most). Check list_categories first: do not propose one that exists.";
    }

    @Override
    public JsonSchema input() {
        return JsonSchema.object(null)
                .required("name", JsonSchema.string("Its name, e.g. «باشگاه»").maxLength(60))
                .required("kind", JsonSchema.enumOf("EXPENSE or INCOME", List.of("EXPENSE", "INCOME")))
                .optional("parent", Inputs.reference("A top-level category of the same kind to put it under"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<CategoryRequest> payloadType() {
        return CategoryRequest.class;
    }

    @Override
    public List<Prepared<CategoryRequest>> prepare(Request request) {
        JsonNode in = request.input();
        CategoryKind kind = Inputs.enumValue(in, "kind", CategoryKind.class);
        Category parent = request.lookup().category("parent", Inputs.text(in, "parent"), kind);
        String name = Inputs.text(in, "name");
        List<String> warnings = new ArrayList<>();
        String key = PersianText.normalizeForSearch(name);
        if (categories.list(request.userId()).stream().anyMatch(c -> c.getKind() == kind && PersianText.normalizeForSearch(c.getName())
                .equals(key))) {
            warnings.add("دسته‌ای با همین نام دارید؛ مطمئن شوید تکراری نیست.");
        }
        return List.of(Prepared.of(new CategoryRequest(name, kind, parent == null ? null : parent.getId(), null, null), warnings));
    }

    @Override
    public Object execute(long userId, CategoryRequest payload) {
        return categories.create(userId, payload);
    }

    @Override
    public Card card(long userId, CategoryRequest p, Object result) {
        return Card.builder("دسته‌ی «" + p.name() + "»")
                .text("نام", p.name())
                .text("نوع", p.kind() == CategoryKind.INCOME ? "درآمد" : "هزینه")
                .text("زیرمجموعه‌ی", p.parentId() == null ? null : categories.require(userId, p.parentId()).getName())
                .build();
    }

    @Override
    public String link(CategoryRequest payload, Object result) {
        return "/categories";
    }
}
