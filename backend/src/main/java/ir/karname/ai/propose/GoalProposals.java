package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.goal.GoalService;
import ir.karname.goal.GoalService.GoalRequest;
import ir.karname.goal.GoalService.GoalView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** A savings goal, in Toman or in any unit (euros for emigrating, gold coins), tracked from linked accounts or by hand. */
@Component
class GoalProposals implements ProposalKind<GoalRequest> {

    private final GoalService goals;
    private final AccountService accounts;

    GoalProposals(GoalService goals, AccountService accounts) {
        this.goals = goals;
        this.accounts = accounts;
    }

    @Override
    public String kind() {
        return "GOAL";
    }

    @Override
    public String tool() {
        return "propose_goal";
    }

    @Override
    public String label() {
        return "پیشنهاد هدف";
    }

    @Override
    public String description() {
        return "Proposes a savings goal for the user to confirm, e.g. «۱۵ هزار یورو برای مهاجرت تا آخر ۱۴۰۶». Progress comes from the "
                + "linked accounts (converted at their prices) or, without accounts, from an amount the user says is saved. The app "
                + "works out progress and what is needed per month.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("name", JsonSchema.string("Short name, e.g. «صندوق مهاجرت»").maxLength(100));
        Inputs.quantity(b, "target", "The target amount, in the goal's unit", true);
        Inputs.quantity(b, "saved_so_far", "Only without accounts: how much is already saved, in the goal's unit", false);
        return b.optional("unit", JsonSchema.string("The goal's unit as a code or name (USD, «یورو», GOLD18, «سکه امامی»); default Toman")
                        .maxLength(60))
                .optional("target_date", Inputs.date("When the user wants to reach it; omit when not said"))
                .optional("accounts", JsonSchema.array("Accounts whose balance counts toward it (not debts): id or name of each",
                        JsonSchema.string(null).maxLength(100)).maxItems(20))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<GoalRequest> payloadType() {
        return GoalRequest.class;
    }

    @Override
    public List<Prepared<GoalRequest>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        CommodityView unit = lookup.unit("unit", Inputs.text(in, "unit"));
        String code = unit == null ? "IRT" : unit.code();
        List<Account> linked = lookup.accounts("accounts", Inputs.texts(in, "accounts"), a -> !a.getType().isLiability(),
                "an account that is not a debt");
        BigDecimal saved = Inputs.positive(in, "saved_so_far") ? Inputs.quantity(in, "saved_so_far") : null;
        if (!linked.isEmpty() && saved != null) {
            warnings.add("پیشرفت از حساب‌های انتخاب‌شده حساب می‌شود و مقدار پس‌انداز گفته‌شده کنار گذاشته شد.");
            saved = null;
        }
        if (linked.isEmpty() && saved == null) {
            saved = BigDecimal.ZERO;
        }
        return List.of(Prepared.of(new GoalRequest(Inputs.text(in, "name"), null, Inputs.quantity(in, "target"), code,
                Inputs.date(in, "target_date"), linked.stream().map(Account::getId).toList(), saved, null, null), warnings));
    }

    @Override
    public Object execute(long userId, GoalRequest payload) {
        return goals.create(userId, payload);
    }

    @Override
    public Card card(long userId, GoalRequest p, Object result) {
        GoalView v = (GoalView) result;
        Card.Builder card = Card.builder("هدف «" + p.name() + "»")
                .quantity("مبلغ هدف", p.targetAmount(), p.commodity(), true)
                .date("تا تاریخ", p.targetDate());
        if (!p.accountIds().isEmpty()) {
            card.text("از حساب‌های", p.accountIds().stream().map(id -> "«" + accounts.require(userId, id).getName() + "»")
                    .collect(Collectors.joining("، ")));
        } else {
            card.text("پیشرفت", "دستی (بدون حساب)");
        }
        card.quantity("پس‌انداز فعلی", v.currentAmount(), p.commodity());
        if (v.progress() != null) {
            card.percent("پیشرفت تا امروز", v.progress().movePointRight(2).setScale(1, java.math.RoundingMode.HALF_EVEN));
        }
        if (v.requiredPerMonth() != null) {
            card.quantity("لازم در هر ماه", v.requiredPerMonth(), p.commodity());
            if (!"IRT".equals(p.commodity())) {
                card.money("لازم در هر ماه به تومان (به قیمت امروز)", v.requiredPerMonthToman());
            }
        }
        if (v.etaMonth() != null) {
            try {
                card.text("با روند فعلی می‌رسید در", JalaliMonth.parse(v.etaMonth()).label());
            } catch (IllegalArgumentException e) {
                // no estimate to show
            }
        }
        if (v.missingPrices()) {
            card.warning("برای بعضی حساب‌ها قیمت ثبت نشده؛ پیشرفت کمتر از واقع نشان داده می‌شود.");
        }
        return card.build();
    }

    @Override
    public String link(GoalRequest payload, Object result) {
        return "/goals";
    }
}
