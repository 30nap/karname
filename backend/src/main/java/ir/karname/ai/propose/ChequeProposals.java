package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.Bank;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeService;
import ir.karname.cheque.ChequeService.ChequeRequest;
import ir.karname.cheque.ChequeService.ChequeView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A cheque the user wrote or received; nothing is posted until it clears. */
@Component
class ChequeProposals implements ProposalKind<ChequeRequest> {

    private final ChequeService cheques;
    private final AccountService accounts;
    private final CategoryService categories;

    ChequeProposals(ChequeService cheques, AccountService accounts, CategoryService categories) {
        this.cheques = cheques;
        this.accounts = accounts;
        this.categories = categories;
    }

    @Override
    public String kind() {
        return "CHEQUE";
    }

    @Override
    public String tool() {
        return "propose_cheque";
    }

    @Override
    public String label() {
        return "پیشنهاد چک";
    }

    @Override
    public String description() {
        return "Proposes a cheque the user wrote (ISSUED) or received (RECEIVED), for the user to confirm. Nothing is posted until it "
                + "clears; then the money leaves or enters its account as an expense or income in its category, or as a transfer "
                + "with settles_account (e.g. a debt it pays off). To mark a cheque in the app as cleared, bounced or cancelled, use "
                + "propose_cheque_status.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("direction", JsonSchema.enumOf("ISSUED (written by the user) or RECEIVED (from someone)",
                        List.of("ISSUED", "RECEIVED")));
        Inputs.money(b, "amount", "The cheque's amount", true);
        return b.required("due_date", Inputs.date("Its date (سررسید)"))
                .optional("counterparty", JsonSchema.string("Who it is written to or received from").maxLength(100))
                .optional("account", Inputs.reference("The Toman account it is drawn on or will be deposited to"))
                .optional("settles_account", Inputs.reference("A debt or receivable account it settles, instead of a category"))
                .optional("category", Inputs.reference("Expense category (ISSUED) or income category (RECEIVED) when it settles nothing"))
                .optional("bank", JsonSchema.enumOf("The bank: " + Labels.banks(), Arrays.stream(Bank.values()).map(Enum::name).toList()))
                .optional("sayad_id", JsonSchema.string("The 16-digit Sayad number").maxLength(30))
                .optional("serial", JsonSchema.string("The cheque's serial number").maxLength(30))
                .optional("issue_date", Inputs.date("When it was written"))
                .optional("description", JsonSchema.string("What it is for").maxLength(300))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<ChequeRequest> payloadType() {
        return ChequeRequest.class;
    }

    @Override
    public List<Prepared<ChequeRequest>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        ChequeDirection direction = Inputs.enumValue(in, "direction", ChequeDirection.class);
        Account account = lookup.account("account", Inputs.text(in, "account"), lookup::isTomanAsset, "a Toman account that is not a debt");
        if (account == null) {
            account = lookup.everyday().orElse(null);
            if (account != null) {
                warnings.add("حساب چک مشخص نبود؛ «" + account.getName() + "» در نظر گرفته شد.");
            }
        }
        Account accountRef = account;
        Account settles = lookup.account("settles_account", Inputs.text(in, "settles_account"),
                a -> "IRT".equals(lookup.unitOf(a)) && !a.equals(accountRef), "another Toman account");
        Category category = settles != null ? null : lookup.category("category", Inputs.text(in, "category"),
                direction == ChequeDirection.ISSUED ? CategoryKind.EXPENSE : CategoryKind.INCOME);
        return List.of(Prepared.of(new ChequeRequest(direction, Inputs.text(in, "sayad_id"), Inputs.text(in, "serial"),
                Inputs.enumValue(in, "bank", Bank.class), account == null ? null : account.getId(), settles == null ? null : settles.getId(),
                category == null ? null : category.getId(), Inputs.text(in, "counterparty"),
                Inputs.toman(in, "amount").setScale(0, RoundingMode.HALF_EVEN), Inputs.date(in, "issue_date"), Inputs.date(in, "due_date"),
                Inputs.text(in, "description"), null), warnings));
    }

    @Override
    public Object execute(long userId, ChequeRequest payload) {
        return cheques.create(userId, payload);
    }

    @Override
    public Card card(long userId, ChequeRequest p, Object result) {
        ChequeView v = (ChequeView) result;
        boolean issued = p.direction() == ChequeDirection.ISSUED;
        Card.Builder card = Card.builder("چک " + Labels.direction(p.direction()) + (p.counterparty() != null
                        ? (issued ? " به " : " از ") + p.counterparty() : ""))
                .money("مبلغ", p.amount(), true)
                .date("سررسید", p.dueDate())
                .text("نوع", Labels.direction(p.direction()))
                .text(issued ? "در وجه" : "از طرف", p.counterparty())
                .text(issued ? "از حساب" : "واریز به", name(userId, p.accountId()))
                .text("تسویه‌ی حساب", name(userId, p.counterAccountId()))
                .text("دسته", p.categoryId() == null ? null : categories.require(userId, p.categoryId()).getName())
                .text("بانک", Labels.bank(p.bank()))
                .text("شناسه‌ی صیادی", v.sayadId())
                .text("سریال", p.serial())
                .date("تاریخ صدور", p.issueDate())
                .text("شرح", p.description());
        if (v.overdue()) {
            card.warning("سررسید این چک گذشته است؛ اگر پاس شده، بعد از ثبت وضعیتش را «پاس‌شده» کنید.");
        }
        card.effect("چک در فهرست چک‌ها و پیش‌بینی نقدینگی می‌آید؛ تا پاس شدن، تراکنشی ثبت نمی‌شود.");
        return card.build();
    }

    private String name(long userId, Long accountId) {
        return accountId == null ? null : accounts.require(userId, accountId).getName();
    }

    @Override
    public String link(ChequeRequest payload, Object result) {
        return "/cheques";
    }
}
