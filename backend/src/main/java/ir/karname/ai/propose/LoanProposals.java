package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.Bank;
import ir.karname.ai.llm.JsonSchema;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.loan.LoanMethod;
import ir.karname.loan.LoanService;
import ir.karname.loan.LoanService.InstallmentView;
import ir.karname.loan.LoanService.LoanRequest;
import ir.karname.loan.LoanService.LoanView;
import ir.karname.loan.LoanService.StartMode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A loan with its installment schedule: one being received now (the money arrives in an account) or
 * one already being repaid. The schedule, installment and interest come from the loan service itself.
 */
@Component
class LoanProposals implements ProposalKind<LoanRequest> {

    private final LoanService loans;
    private final AccountService accounts;

    LoanProposals(LoanService loans, AccountService accounts) {
        this.loans = loans;
        this.accounts = accounts;
    }

    @Override
    public String kind() {
        return "LOAN";
    }

    @Override
    public String tool() {
        return "propose_loan";
    }

    @Override
    public String label() {
        return "پیشنهاد وام";
    }

    @Override
    public String description() {
        return "Proposes a loan for the user to confirm, with its installment schedule: a bank loan, a Qard al-Hasan loan (its fee as "
                + "the rate) or a loan from a fund. Use it both for a loan being received now and for one the user is already "
                + "repaying. The app works out the installments, interest and remaining debt; never compute them yourself. Ask the user "
                + "for the amount, rate, number of installments and first due date when they did not say them.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .optional("name", JsonSchema.string("Short name, e.g. «وام مسکن ملی»; default «وام» and the bank").maxLength(100))
                .optional("bank", JsonSchema.enumOf("The lending bank: " + Labels.banks(), Arrays.stream(Bank.values()).map(Enum::name).toList()))
                .optional("counterparty", JsonSchema.string("For a loan from a person or a fund (صندوق): who").maxLength(100));
        Inputs.money(b, "principal", "The loan amount", true);
        b.required("annual_rate_percent", JsonSchema.number("Yearly rate in percent: the bank's interest, or the Qard al-Hasan fee "
                        + "(e.g. 4); 0 when none").min(0).max(100))
                .required("term_months", JsonSchema.integer("Number of monthly installments").min(1).max(600))
                .required("first_due_date", Inputs.date("Due date of the first installment"))
                .optional("method", JsonSchema.enumOf("ANNUITY (default: equal installments, as Iranian banks use) or EQUAL_PRINCIPAL "
                        + "(decreasing installments)", List.of("ANNUITY", "EQUAL_PRINCIPAL")));
        Inputs.money(b, "installment", "Only when the user stated it: the installment the bank set, if it differs from the formula", false);
        return b.optional("status", JsonSchema.enumOf("NEW when the money is being received now, EXISTING when the user is already "
                                + "repaying it; default EXISTING when the first installment is due by today, else NEW",
                        List.of("NEW", "EXISTING")))
                .optional("installments_already_paid", JsonSchema.integer("EXISTING: installments paid before; default those due "
                        + "by today").min(0).max(599))
                .optional("deposit_account", Inputs.reference("NEW: the Toman account the money arrives in; default the everyday account"))
                .optional("received_date", Inputs.date("NEW: when the money arrived; default today"))
                .optional("payment_account", Inputs.reference("The Toman account installments are paid from; default the deposit or "
                        + "everyday account"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<LoanRequest> payloadType() {
        return LoanRequest.class;
    }

    @Override
    public List<Prepared<LoanRequest>> prepare(Request request) {
        JsonNode in = request.input();
        Resolver.Lookup lookup = request.lookup();
        LocalDate today = request.today();
        List<String> warnings = new ArrayList<>();
        BigDecimal principal = Inputs.toman(in, "principal").setScale(0, RoundingMode.HALF_EVEN);
        BigDecimal rate = in.get("annual_rate_percent").decimalValue().stripTrailingZeros();
        int term = in.get("term_months").asInt();
        LocalDate firstDue = Inputs.date(in, "first_due_date");
        BigDecimal installment = Inputs.toman(in, "installment");
        if (installment != null) {
            installment = installment.setScale(0, RoundingMode.HALF_EVEN);
        }
        Bank bank = Inputs.enumValue(in, "bank", Bank.class);
        String name = Inputs.text(in, "name");
        if (name == null) {
            name = bank != null ? "وام " + Labels.bank(bank) : "وام";
        }
        if (rate.compareTo(BigDecimal.valueOf(40)) > 0) {
            warnings.add("نرخ سود " + rate.toPlainString() + "٪ در سال غیرعادی بالاست؛ آن را بررسی کنید.");
        }

        StartMode start = Inputs.enumValue(in, "status", StartMode.class);
        if (start == null) {
            start = firstDue.isAfter(today) ? StartMode.NEW : StartMode.EXISTING;
        }
        Integer paidBefore = null;
        Long depositId = null;
        LocalDate received = null;
        Account deposit = null;
        if (start == StartMode.EXISTING) {
            paidBefore = Inputs.integer(in, "installments_already_paid");
            if (paidBefore == null) {
                paidBefore = dueBy(firstDue, term, today);
                if (paidBefore > 0) {
                    warnings.add("فرض شد " + paidBefore + " قسطی که تا امروز سررسید شده پرداخت شده است؛ اگر نه، به دستیار بگویید.");
                }
            }
            if (paidBefore >= term) {
                throw new IllegalArgumentException("installments_already_paid: all " + term + " installments would be paid, so there is "
                        + "nothing to track; check the first due date and the number of installments with the user");
            }
        } else {
            deposit = lookup.account("deposit_account", Inputs.text(in, "deposit_account"), lookup::isTomanAsset,
                    "a Toman account that is not a debt");
            if (deposit == null) {
                deposit = lookup.everyday().orElseThrow(() -> new IllegalArgumentException("deposit_account: the user has no Toman bank, "
                        + "wallet or cash account for the money to arrive in; propose one with propose_account first"));
                warnings.add("حساب واریز مشخص نبود؛ «" + deposit.getName() + "» در نظر گرفته شد.");
            }
            depositId = deposit.getId();
            received = Inputs.date(in, "received_date");
            if (received == null) {
                received = today;
            }
        }
        Account payment = lookup.account("payment_account", Inputs.text(in, "payment_account"), lookup::isTomanAsset,
                "a Toman account that is not a debt");
        if (payment == null) {
            payment = deposit != null ? deposit : lookup.everyday().orElse(null);
            if (payment != null && deposit == null) {
                warnings.add("حساب پرداخت اقساط مشخص نبود؛ «" + payment.getName() + "» در نظر گرفته شد.");
            }
        }
        LoanMethod method = Inputs.enumValue(in, "method", LoanMethod.class);
        return List.of(Prepared.of(new LoanRequest(name, bank, Inputs.text(in, "counterparty"), principal, rate, term, firstDue,
                method == null ? LoanMethod.ANNUITY : method, installment, payment == null ? null : payment.getId(), null, start, depositId,
                received, paidBefore), warnings));
    }

    /** How many monthly installments from {@code firstDue} fall on or before {@code today}. */
    static int dueBy(LocalDate firstDue, int term, LocalDate today) {
        JalaliDate first = JalaliDate.from(firstDue);
        int count = 0;
        while (count < term && !first.plusMonths(count).toGregorian().isAfter(today)) {
            count++;
        }
        return count;
    }

    @Override
    public Object execute(long userId, LoanRequest payload) {
        return loans.create(userId, payload);
    }

    @Override
    public Card card(long userId, LoanRequest p, Object result) {
        LoanView view = (LoanView) result;
        List<InstallmentView> schedule = view.installments();
        Card.Builder card = Card.builder(p.name())
                .money("مبلغ وام", p.principal(), true)
                .percent("نرخ سالانه", p.annualRate())
                .number("تعداد اقساط", p.termMonths())
                .text("روش", Labels.method(p.method()))
                .date("سررسید قسط اول", p.firstDueDate())
                .text("بانک", Labels.bank(p.bank()))
                .text("وام‌دهنده", p.counterparty());
        if (!schedule.isEmpty()) {
            InstallmentView first = schedule.getFirst();
            InstallmentView last = schedule.getLast();
            card.money(p.method() == LoanMethod.ANNUITY ? "مبلغ هر قسط" : "قسط اول", first.amount());
            if (last.amount().compareTo(first.amount()) != 0) {
                card.money("قسط آخر", last.amount());
            }
        }
        card.money("مجموع سود", view.totalInterest()).date("سررسید قسط آخر", view.endDate());
        String account = "حساب وام «" + p.name() + "» ساخته می‌شود";
        if (p.start() == StartMode.NEW) {
            card.text("واریز به", name(userId, p.depositAccountId())).date("تاریخ دریافت", p.receivedOn());
            card.effect(account + ".");
            card.effect("مبلغ وام از حساب وام به «" + name(userId, p.depositAccountId()) + "» منتقل می‌شود.");
        } else {
            card.number("اقساط پرداخت‌شده تا امروز", p.paidBefore()).money("مانده‌ی بدهی امروز", view.outstanding() == null ? null
                    : view.outstanding().abs());
            card.effect(account + " و مانده‌ی بدهی به‌عنوان موجودی اول آن ثبت می‌شود.");
        }
        card.text("پرداخت اقساط از", name(userId, p.paymentAccountId()));
        card.effect("جدول " + p.termMonths() + " قسط ساخته می‌شود؛ پرداخت هر قسط را در صفحه‌ی وام یا با دستیار ثبت کنید.");
        return card.build();
    }

    private String name(long userId, Long accountId) {
        return accountId == null ? null : accounts.require(userId, accountId).getName();
    }

    @Override
    public String link(LoanRequest payload, Object result) {
        return "/loans/" + ((LoanView) result).id();
    }
}
