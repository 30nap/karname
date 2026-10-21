package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.ai.guard.Privacy;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.common.persian.PersianText;
import ir.karname.loan.LoanService;
import ir.karname.loan.LoanService.InstallmentView;
import ir.karname.loan.LoanService.LoanView;
import ir.karname.loan.LoanService.PaymentRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Paying one installment of a loan already in the app: the principal moves to the loan account and the interest is an expense. */
@Component
class LoanPaymentProposals implements ProposalKind<LoanPaymentProposals.Payment> {

    /** Installment {@code number} of loan {@code loanId}, paid from {@code accountId} on {@code date}. */
    public record Payment(long loanId, String loanName, int number, Long accountId, LocalDate date, BigDecimal penalty) {
    }

    private final LoanService loans;
    private final AccountService accounts;

    LoanPaymentProposals(LoanService loans, AccountService accounts) {
        this.loans = loans;
        this.accounts = accounts;
    }

    @Override
    public String kind() {
        return "LOAN_PAYMENT";
    }

    @Override
    public String tool() {
        return "propose_loan_payment";
    }

    @Override
    public String label() {
        return "پیشنهاد پرداخت قسط";
    }

    @Override
    public String description() {
        return "Proposes recording that the user paid an installment of a loan already in the app (see list_loans), for the user to "
                + "confirm. The app splits it into principal and interest from the loan's schedule. Do not use propose_transactions "
                + "for installments of these loans.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("loan", Inputs.reference("The loan"))
                .optional("installment_number", JsonSchema.integer("Which installment; default the next unpaid one").min(1).max(600))
                .optional("account", Inputs.reference("The Toman account it was paid from; default the loan's payment account"))
                .optional("date", Inputs.date("When it was paid; default today"));
        Inputs.money(b, "penalty", "A late fee paid with it, if any", false);
        return b.optional("replaces_previous", Inputs.replacesPrevious()).build();
    }

    @Override
    public Class<Payment> payloadType() {
        return Payment.class;
    }

    @Override
    public List<Prepared<Payment>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        List<String> warnings = new ArrayList<>();
        LoanView loan = loan(request.userId(), Inputs.text(in, "loan"));
        Integer number = Inputs.integer(in, "installment_number");
        if (number == null) {
            if (loan.next() == null) {
                throw new IllegalArgumentException("loan: every installment of «" + Privacy.mask(loan.name()) + "» is already paid");
            }
            number = loan.next().number();
        }
        Account account = lookup.account("account", Inputs.text(in, "account"), lookup::isTomanAsset, "a Toman account that is not a debt");
        Long accountId = account != null ? account.getId() : loan.paymentAccountId();
        if (accountId == null) {
            Account everyday = lookup.everyday().orElseThrow(() -> new IllegalArgumentException("account: the loan has no payment account "
                    + "and the user has no Toman bank, wallet or cash account; ask which account paid it"));
            accountId = everyday.getId();
            warnings.add("حساب پرداخت مشخص نبود؛ «" + everyday.getName() + "» در نظر گرفته شد.");
        }
        LocalDate date = Inputs.date(in, "date");
        return List.of(Prepared.of(new Payment(loan.id(), loan.name(), number, accountId, date == null ? request.today() : date,
                Inputs.toman(in, "penalty")), warnings));
    }

    private LoanView loan(long userId, String ref) {
        List<LoanView> all = loans.list(userId);
        if (ref != null && ref.matches("[0-9۰-۹]{1,18}")) {
            long id = Long.parseLong(PersianText.normalizeDigits(ref));
            for (LoanView l : all) {
                if (l.id() == id) {
                    return l;
                }
            }
        }
        String q = PersianText.normalizeForSearch(ref);
        List<LoanView> exact = all.stream().filter(l -> PersianText.normalizeForSearch(l.name()).equals(q)).toList();
        List<LoanView> found = exact.isEmpty()
                ? all.stream().filter(l -> {
                    String n = PersianText.normalizeForSearch(l.name());
                    return q.length() >= 2 && (n.contains(q) || q.contains(n));
                }).toList()
                : exact;
        if (found.size() == 1) {
            return found.getFirst();
        }
        String list = all.stream().map(l -> l.id() + " «" + Privacy.mask(l.name()) + "»").collect(Collectors.joining(", "));
        throw new IllegalArgumentException("loan: " + (found.isEmpty() ? "no loan matches" : "several loans match") + " «" + ref + "»"
                + (all.isEmpty() ? "; the user has no loans in the app (propose_loan adds one)" : ". Loans: " + list));
    }

    @Override
    public Object execute(long userId, Payment p) {
        return loans.pay(userId, p.loanId(), p.number(), new PaymentRequest(p.accountId(), p.date(), p.penalty()));
    }

    @Override
    public Card card(long userId, Payment p, Object result) {
        LoanView view = (LoanView) result;
        InstallmentView installment = view.installments().stream().filter(i -> i.number() == p.number()).findFirst().orElseThrow();
        String from = accounts.require(userId, p.accountId()).getName();
        Card.Builder card = Card.builder("قسط " + p.number() + " «" + view.name() + "»")
                .money("مبلغ قسط", installment.amount(), true)
                .money("اصل", installment.principal())
                .money("سود", installment.interest())
                .money("جریمه‌ی دیرکرد", p.penalty())
                .date("سررسید", installment.dueDate())
                .date("تاریخ پرداخت", p.date())
                .text("از حساب", from);
        if (installment.principal().signum() > 0) {
            card.effect("اصل قسط از «" + from + "» به حساب وام منتقل می‌شود و بدهی وام همین‌قدر کم می‌شود.");
        }
        if (installment.interest().signum() > 0 || p.penalty() != null) {
            card.effect("سود" + (p.penalty() != null ? " و جریمه" : "") + " به‌عنوان هزینه از «" + from + "» ثبت می‌شود.");
        }
        if (installment.dueDate().isAfter(p.date().plusDays(31))) {
            card.warning("سررسید این قسط هنوز نرسیده است؛ مطمئن شوید همین قسط را پرداخته‌اید.");
        }
        return card.build();
    }

    @Override
    public String link(Payment payload, Object result) {
        return "/loans/" + payload.loanId();
    }
}
