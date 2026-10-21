package ir.karname.ai.propose;

import ir.karname.ai.llm.JsonSchema;
import ir.karname.budget.BudgetService;
import ir.karname.budget.BudgetService.BudgetItem;
import ir.karname.budget.BudgetService.BudgetMonth;
import ir.karname.budget.BudgetService.BudgetRequest;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.common.jalali.JalaliMonth;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Monthly budgets for expense categories, several at once; one card each. */
@Component
class BudgetProposals implements ProposalKind<BudgetProposals.Budget> {

    /** {@code month} as yyyy-mm; {@code recurring}: also for the months after, until changed. */
    public record Budget(String month, long categoryId, String categoryName, BigDecimal amount, boolean recurring) {
    }

    /** The category's budget line before and after. */
    record Outcome(BudgetItem before, BudgetItem after) {
    }

    private final BudgetService budgets;

    BudgetProposals(BudgetService budgets) {
        this.budgets = budgets;
    }

    @Override
    public String kind() {
        return "BUDGET";
    }

    @Override
    public String tool() {
        return "propose_budgets";
    }

    @Override
    public String label() {
        return "پیشنهاد بودجه";
    }

    @Override
    public String description() {
        return "Proposes monthly budgets for expense categories, one card each for the user to confirm. A budget on a parent category "
                + "covers its subcategories. By default it applies from that month on; only_this_month limits it to one month. "
                + "Changing an existing budget is the same call with the new amount.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder item = JsonSchema.object("One category's budget")
                .required("category", Inputs.reference("The expense category"));
        Inputs.money(item, "amount", "The monthly budget", true);
        item.optional("month", JsonSchema.string("Jalali month as yyyy-mm; default this month").maxLength(7))
                .optional("only_this_month", JsonSchema.bool("True for this month only; default false (this month and the ones after)"));
        return JsonSchema.object(null)
                .required("budgets", JsonSchema.array("The budgets", item.build()).maxItems(20))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<Budget> payloadType() {
        return Budget.class;
    }

    @Override
    public List<Prepared<Budget>> prepare(Request request) {
        Resolver.Lookup lookup = request.lookup();
        JalaliMonth current = JalaliMonth.from(request.today());
        List<Prepared<Budget>> out = new ArrayList<>();
        for (JsonNode item : request.input().get("budgets")) {
            try {
                Category category = lookup.category("category", Inputs.text(item, "category"), CategoryKind.EXPENSE);
                if (category == null) {
                    throw new IllegalArgumentException("category: required");
                }
                JalaliMonth month = Inputs.month(item, "month", current);
                List<String> warnings = new ArrayList<>();
                if (month.compareTo(current) < 0) {
                    warnings.add("این بودجه برای ماهی گذشته است.");
                }
                out.add(Prepared.of(new Budget(month.toString(), category.getId(), category.getName(), Inputs.toman(item, "amount"),
                        !Inputs.flag(item, "only_this_month")), warnings));
            } catch (IllegalArgumentException e) {
                out.add(Prepared.rejected(e.getMessage()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("budgets: give at least one");
        }
        return out;
    }

    @Override
    public Object execute(long userId, Budget p) {
        JalaliMonth month = JalaliMonth.parse(p.month());
        BudgetItem before = item(budgets.month(userId, month), p.categoryId());
        budgets.set(userId, month, p.categoryId(), new BudgetRequest(p.amount(), p.recurring()));
        return new Outcome(before, item(budgets.month(userId, month), p.categoryId()));
    }

    private static BudgetItem item(BudgetMonth month, long categoryId) {
        return month.items().stream().filter(i -> i.categoryId() == categoryId).findFirst().orElse(null);
    }

    @Override
    public Card card(long userId, Budget p, Object result) {
        Outcome o = (Outcome) result;
        String category = o.after() != null && o.after().parentName() != null ? o.after().parentName() + " › " + p.categoryName()
                : p.categoryName();
        String month = JalaliMonth.parse(p.month()).label();
        Card.Builder card = Card.builder("بودجه‌ی «" + category + "»، " + month)
                .money("بودجه‌ی ماهانه", p.amount(), true)
                .text("ماه", month)
                .text("دوره", p.recurring() ? "از این ماه به بعد" : "فقط همین ماه");
        if (o.before() != null && o.before().amount() != null && o.before().amount().signum() > 0) {
            card.money("بودجه‌ی قبلی", o.before().amount());
        }
        if (o.after() != null) {
            card.money("خرج‌شده در این ماه", o.after().spent());
            card.money("باقی‌مانده", o.after().remaining());
            if (o.after().spent() != null && o.after().spent().compareTo(p.amount()) > 0) {
                card.warning("خرج این ماه همین حالا از این بودجه بیشتر است.");
            }
        }
        return card.build();
    }

    @Override
    public String link(Budget payload, Object result) {
        return "/budgets";
    }
}
