package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.ai.guard.Privacy;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeService;
import ir.karname.cheque.ChequeService.ChequeView;
import ir.karname.cheque.ChequeService.StatusRequest;
import ir.karname.cheque.ChequeStatus;
import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** A cheque already in the app cleared, bounced, was cancelled, or goes back to waiting. */
@Component
class ChequeStatusProposals implements ProposalKind<ChequeStatusProposals.Change> {

    public record Change(long chequeId, ChequeStatus status, LocalDate date, Long accountId) {
    }

    record Outcome(ChequeView before, ChequeView after) {
    }

    private final ChequeService cheques;
    private final AccountService accounts;

    ChequeStatusProposals(ChequeService cheques, AccountService accounts) {
        this.cheques = cheques;
        this.accounts = accounts;
    }

    @Override
    public String kind() {
        return "CHEQUE_STATUS";
    }

    @Override
    public String tool() {
        return "propose_cheque_status";
    }

    @Override
    public String label() {
        return "پیشنهاد وضعیت چک";
    }

    @Override
    public String description() {
        return "Proposes a new status for a cheque already in the app (see list_cheques), for the user to confirm: CLEARED (پاس شد; "
                + "the money then leaves or enters its account), BOUNCED (برگشت خورد), CANCELLED (باطل شد) or back to PENDING.";
    }

    @Override
    public JsonSchema input() {
        return JsonSchema.object(null)
                .required("cheque", JsonSchema.string("The cheque: its id from list_cheques, or who it is to or from").maxLength(100))
                .required("status", JsonSchema.enumOf("The new status", List.of("CLEARED", "BOUNCED", "CANCELLED", "PENDING")))
                .optional("date", Inputs.date("When it happened; default today"))
                .optional("account", Inputs.reference("CLEARED: the Toman account the money left or entered; default the cheque's own"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<Change> payloadType() {
        return Change.class;
    }

    @Override
    public List<Prepared<Change>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        ChequeView cheque = cheque(request.userId(), Inputs.text(in, "cheque"));
        ChequeStatus status = Inputs.enumValue(in, "status", ChequeStatus.class);
        if (cheque.status() == status) {
            throw new IllegalArgumentException("status: the cheque is already " + status);
        }
        Long accountId = null;
        if (status == ChequeStatus.CLEARED) {
            Account account = lookup.account("account", Inputs.text(in, "account"), lookup::isTomanAsset, "a Toman account that is not a debt");
            accountId = account != null ? account.getId() : cheque.accountId();
            if (accountId == null) {
                Account everyday = lookup.everyday().orElseThrow(() -> new IllegalArgumentException("account: the cheque has no account; "
                        + "ask which account the money left or entered"));
                accountId = everyday.getId();
                warnings.add("حساب چک مشخص نبود؛ «" + everyday.getName() + "» در نظر گرفته شد.");
            }
        }
        LocalDate date = Inputs.date(in, "date");
        return List.of(Prepared.of(new Change(cheque.id(), status, status == ChequeStatus.PENDING ? null : date == null ? request.today() : date,
                accountId), warnings));
    }

    private ChequeView cheque(long userId, String ref) {
        List<ChequeView> all = cheques.list(userId);
        if (ref.matches("[0-9۰-۹]{1,18}")) {
            long id = Long.parseLong(PersianText.normalizeDigits(ref));
            for (ChequeView c : all) {
                if (c.id() == id) {
                    return c;
                }
            }
        }
        String q = PersianText.normalizeForSearch(ref);
        List<ChequeView> found = all.stream().filter(c -> matches(c, q)).toList();
        List<ChequeView> pending = found.stream().filter(c -> c.status() == ChequeStatus.PENDING).toList();
        if (found.size() == 1) {
            return found.getFirst();
        }
        if (pending.size() == 1) {
            return pending.getFirst();
        }
        String list = (found.isEmpty() ? all : found).stream().sorted(Comparator.comparing(ChequeView::dueDate)).limit(20)
                .map(c -> c.id() + " «" + Privacy.mask((c.counterparty() == null ? "" : c.counterparty() + " ") + c.dueDate() + " "
                        + c.amount().toPlainString()) + "» " + c.status()).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("cheque: " + (found.isEmpty() ? "no cheque matches" : "several cheques match") + " «" + ref + "»"
                + (all.isEmpty() ? "; the user has no cheques in the app (propose_cheque adds one)" : ". Cheques: " + list
                + ". Pass the id of the one meant, or ask the user."));
    }

    private static boolean matches(ChequeView c, String q) {
        if (q.length() < 2) {
            return false;
        }
        for (String text : new String[] {c.counterparty(), c.description(), c.serial()}) {
            String n = PersianText.normalizeForSearch(text);
            if (!n.isEmpty() && (n.contains(q) || q.contains(n))) {
                return true;
            }
        }
        return c.sayadId() != null && q.matches(".*[0-9]{4}.*") && q.contains(c.sayadId().substring(c.sayadId().length() - 4));
    }

    @Override
    public Object execute(long userId, Change p) {
        ChequeView before = cheques.get(userId, p.chequeId());
        return new Outcome(before, cheques.changeStatus(userId, p.chequeId(), new StatusRequest(p.status(), p.date(), p.accountId())));
    }

    @Override
    public Card card(long userId, Change p, Object result) {
        Outcome o = (Outcome) result;
        ChequeView c = o.after();
        boolean issued = c.direction() == ChequeDirection.ISSUED;
        String account = c.accountId() == null ? null : accounts.require(userId, c.accountId()).getName();
        Card.Builder card = Card.builder("چک " + Labels.direction(c.direction()) + (c.counterparty() != null
                        ? (issued ? " به " : " از ") + c.counterparty() : "") + ": " + Labels.chequeStatus(p.status()))
                .money("مبلغ", c.amount(), true)
                .text("وضعیت فعلی", Labels.chequeStatus(o.before().status()))
                .text("وضعیت تازه", Labels.chequeStatus(p.status()))
                .date("سررسید", c.dueDate())
                .date("تاریخ", p.date());
        if (p.status() == ChequeStatus.CLEARED) {
            card.text(issued ? "از حساب" : "به حساب", account);
            String other = c.counterAccountId() != null ? "«" + accounts.require(userId, c.counterAccountId()).getName() + "»" : null;
            card.effect(issued
                    ? (other != null ? "مبلغ از «" + account + "» به " + other + " منتقل می‌شود." : "مبلغ به‌عنوان هزینه از «" + account + "» ثبت می‌شود.")
                    : (other != null ? "مبلغ از " + other + " به «" + account + "» منتقل می‌شود." : "مبلغ به‌عنوان درآمد به «" + account + "» ثبت می‌شود."));
        }
        if (o.before().status() == ChequeStatus.CLEARED) {
            card.effect("تراکنشی که با پاس شدن این چک ثبت شده بود حذف می‌شود.");
        }
        return card.build();
    }

    @Override
    public String link(Change payload, Object result) {
        return "/cheques";
    }
}
