package ir.karname.loan;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.account.Bank;
import ir.karname.account.BalanceService;
import ir.karname.category.Category;
import ir.karname.category.CategoryService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityService;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.transaction.Transaction;
import ir.karname.transaction.TransactionRepository;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionSource;
import ir.karname.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loans with installment schedules. Paying installment n records a transfer of its principal into
 * the loan account ({@code loan:{id}:{n}:p}) and an interest expense ({@code loan:{id}:{n}:i});
 * an installment is paid exactly when those transactions exist, so deleting them un-pays it.
 */
@Service
public class LoanService {

    public static final String INTEREST_CATEGORY_KEY = "loan_interest";
    private static final int DUE_SOON_DAYS = 7;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");

    private final LoanRepository loans;
    private final LoanInstallmentRepository installments;
    private final AccountService accounts;
    private final BalanceService balances;
    private final CommodityService commodities;
    private final CategoryService categories;
    private final TransactionService transactionService;
    private final TransactionRepository transactions;
    private final Clock clock;

    public LoanService(LoanRepository loans, LoanInstallmentRepository installments, AccountService accounts, BalanceService balances,
            CommodityService commodities, CategoryService categories, TransactionService transactionService,
            TransactionRepository transactions, Clock clock) {
        this.loans = loans;
        this.installments = installments;
        this.accounts = accounts;
        this.balances = balances;
        this.commodities = commodities;
        this.categories = categories;
        this.transactionService = transactionService;
        this.transactions = transactions;
        this.clock = clock;
    }

    public enum StartMode {
        /** The money arrives now: a transfer from the loan account into {@code depositAccountId}. */
        NEW,
        /** A loan already being repaid: the outstanding principal becomes the account's opening balance. */
        EXISTING
    }

    public enum InstallmentStatus {
        PAID_BEFORE, PAID, OVERDUE, DUE_SOON, UPCOMING
    }

    public record LoanRequest(String name, Bank bank, String counterparty, BigDecimal principal, BigDecimal annualRate,
            Integer termMonths, LocalDate firstDueDate, LoanMethod method, BigDecimal installmentAmount, Long paymentAccountId,
            String notes, StartMode start, Long depositAccountId, LocalDate receivedOn, Integer paidBefore) {
    }

    public record PaymentRequest(Long accountId, LocalDate date, BigDecimal penalty) {
    }

    public record InstallmentView(int number, LocalDate dueDate, BigDecimal amount, BigDecimal principal, BigDecimal interest,
            BigDecimal balanceAfter, InstallmentStatus status, LocalDate paidOn, BigDecimal paidAmount) {
    }

    /** {@code outstanding} is read from the loan account; {@code installments} is null in lists. */
    public record LoanView(long id, long accountId, String name, Bank bank, String counterparty, BigDecimal principal,
            BigDecimal annualRate, int termMonths, LocalDate firstDueDate, LoanMethod method, BigDecimal installmentAmount,
            int paidBefore, Long paymentAccountId, String notes, BigDecimal outstanding, BigDecimal totalInterest,
            BigDecimal remainingInterest, int paidCount, int overdueCount, BigDecimal overdueAmount, InstallmentView next,
            LocalDate endDate, List<InstallmentView> installments) {
    }

    /** An unpaid installment, for reminders and the cash-flow forecast. */
    public record DueInstallment(long loanId, String loanName, long loanAccountId, Long paymentAccountId, int number,
            LocalDate dueDate, BigDecimal amount, BigDecimal principal, BigDecimal interest) {
    }

    public record SchedulePreview(BigDecimal firstInstallment, BigDecimal lastInstallment, BigDecimal totalInterest,
            BigDecimal totalPaid, LocalDate endDate) {
    }

    private record Payment(LocalDate date, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<LoanView> list(long userId) {
        return loans.findByUserIdOrderByIdAsc(userId).stream().map(l -> view(userId, l, false)).toList();
    }

    @Transactional(readOnly = true)
    public LoanView get(long userId, long id) {
        return view(userId, require(userId, id), true);
    }

    public SchedulePreview preview(BigDecimal principal, BigDecimal annualRate, Integer termMonths, LoanMethod method,
            BigDecimal installmentAmount, LocalDate firstDue) {
        validateTerms(principal, annualRate, termMonths, firstDue, installmentAmount);
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(principal, annualRate, termMonths,
                method == null ? LoanMethod.ANNUITY : method, installmentAmount, firstDue);
        BigDecimal interest = lines.stream().map(LoanSchedule.Line::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SchedulePreview(lines.getFirst().amount(), lines.getLast().amount(), interest, principal.add(interest),
                lines.getLast().dueDate());
    }

    @Transactional
    public LoanView create(long userId, LoanRequest r) {
        validateTerms(r.principal(), r.annualRate(), r.termMonths(), r.firstDueDate(), r.installmentAmount());
        LoanMethod method = r.method() == null ? LoanMethod.ANNUITY : r.method();
        StartMode mode = r.start() == null ? StartMode.NEW : r.start();
        int paidBefore = mode == StartMode.EXISTING && r.paidBefore() != null ? r.paidBefore() : 0;
        if (paidBefore < 0 || paidBefore >= r.termMonths()) {
            throw ApiException.badRequest("loan.invalidPaidBefore");
        }
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(r.principal(), r.annualRate(), r.termMonths(), method, r.installmentAmount(),
                r.firstDueDate());
        Long paymentAccountId = paymentAccount(userId, r.paymentAccountId());
        Account deposit = null;
        if (mode == StartMode.NEW) {
            if (r.depositAccountId() == null) {
                throw ApiException.badRequest("loan.depositAccountRequired");
            }
            deposit = tomanAccount(userId, r.depositAccountId(), "loan.depositAccountToman");
        }
        BigDecimal opening = mode == StartMode.EXISTING ? (paidBefore == 0 ? r.principal() : lines.get(paidBefore - 1).balanceAfter()) : null;
        Account account = accounts.create(userId, new AccountRequest(r.name(), AccountType.LOAN, Commodity.TOMAN, r.bank(), null,
                r.counterparty(), null, true, null, opening, opening == null ? null : LocalDate.now(clock)));

        Loan loan = new Loan(userId, account.getId());
        applyTerms(loan, r, method, paidBefore);
        loan.setPaymentAccountId(paymentAccountId);
        loan.setNotes(notes(r.notes()));
        loans.saveAndFlush(loan);
        saveSchedule(loan, lines);
        if (deposit != null) {
            LocalDate received = r.receivedOn() != null ? r.receivedOn() : LocalDate.now(clock);
            transactionService.create(userId, new TransactionRequest(TransactionType.TRANSFER, received, account.getId(), r.principal(),
                    deposit.getId(), null, null, null, "دریافت " + account.getName(), null, List.of()), TransactionSource.LOAN,
                    disbursementRef(loan));
        }
        return view(userId, loan, true);
    }

    @Transactional
    public LoanView update(long userId, long id, LoanRequest r) {
        Loan loan = require(userId, id);
        validateTerms(r.principal(), r.annualRate(), r.termMonths(), r.firstDueDate(), r.installmentAmount());
        LoanMethod method = r.method() == null ? LoanMethod.ANNUITY : r.method();
        int paidBefore = r.paidBefore() != null ? r.paidBefore() : loan.getPaidBefore();
        if (paidBefore < 0 || paidBefore >= r.termMonths()) {
            throw ApiException.badRequest("loan.invalidPaidBefore");
        }
        List<LoanSchedule.Line> lines = LoanSchedule.schedule(r.principal(), r.annualRate(), r.termMonths(), method, r.installmentAmount(),
                r.firstDueDate());
        Account account = accounts.require(userId, loan.getAccountId());
        accounts.update(userId, account.getId(), new AccountRequest(r.name(), AccountType.LOAN, null, r.bank(), account.getIdentifierHints(),
                r.counterparty(), account.getIcon(), account.isIncludeInNetWorth(), account.getNotes(), null, null));
        boolean principalChanged = loan.getPrincipal().compareTo(r.principal()) != 0;
        applyTerms(loan, r, method, paidBefore);
        loan.setPaymentAccountId(paymentAccount(userId, r.paymentAccountId()));
        loan.setNotes(notes(r.notes()));
        installments.deleteByLoan(loan.getId());
        installments.flush();
        saveSchedule(loan, lines);
        if (principalChanged) {
            // keep the recorded disbursement in line with the corrected principal
            transactions.findByUserIdAndExternalRef(userId, disbursementRef(loan)).ifPresent(tx -> transactionService.update(userId, tx.getId(),
                    new TransactionRequest(TransactionType.TRANSFER, tx.getOccurredOn(), tx.getAccountId(), r.principal(), tx.getToAccountId(), null,
                            null, null, tx.getDescription(), tx.getNotes(), tx.getTags())));
        }
        return view(userId, loan, true);
    }

    /**
     * Removes the loan's terms and schedule. With {@code withAccount} the loan is erased as if never recorded: its account,
     * the disbursement, every installment payment and the interest expenses booked on other accounts.
     */
    @Transactional
    public void delete(long userId, long id, boolean withAccount) {
        Loan loan = require(userId, id);
        if (withAccount) {
            transactions.findByUserIdAndExternalRefStartingWith(userId, "loan:" + loan.getId() + ":")
                    .forEach(tx -> transactionService.delete(userId, tx.getId()));
            accounts.delete(userId, loan.getAccountId(), true);
        } else {
            loans.delete(loan);
        }
    }

    @Transactional
    public LoanView pay(long userId, long id, int number, PaymentRequest request) {
        Loan loan = require(userId, id);
        LoanInstallment installment = installment(loan, number);
        if (number <= loan.getPaidBefore()) {
            throw ApiException.conflict("loan.paidBefore");
        }
        if (payments(userId, loan).containsKey(number)) {
            throw ApiException.conflict("loan.alreadyPaid");
        }
        Long accountId = request.accountId() != null ? request.accountId() : loan.getPaymentAccountId();
        if (accountId == null) {
            throw ApiException.badRequest("loan.paymentAccountRequired");
        }
        Account from = tomanAccount(userId, accountId, "loan.paymentAccountToman");
        if (from.getType().isLiability()) {
            throw ApiException.badRequest("loan.paymentAccountToman");
        }
        BigDecimal penalty = request.penalty() == null ? BigDecimal.ZERO : request.penalty();
        if (penalty.signum() < 0 || penalty.compareTo(MAX_AMOUNT) >= 0) {
            throw ApiException.badRequest("loan.invalidPenalty");
        }
        LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
        Account loanAccount = accounts.require(userId, loan.getAccountId());
        String label = "قسط " + PersianText.toPersianDigits(String.valueOf(number)) + " " + loanAccount.getName();
        if (installment.getPrincipal().signum() > 0) {
            transactionService.create(userId, new TransactionRequest(TransactionType.TRANSFER, date, from.getId(), installment.getPrincipal(),
                    loanAccount.getId(), null, null, null, label, null, List.of()), TransactionSource.LOAN, ref(loan, number, "p"));
        }
        BigDecimal cost = installment.getInterest().add(penalty);
        if (cost.signum() > 0) {
            Long category = categories.bySystemKey(userId, INTEREST_CATEGORY_KEY).map(Category::getId).orElse(null);
            String description = penalty.signum() > 0 ? label + " (سود و جریمه)" : label + " (سود)";
            transactionService.create(userId, new TransactionRequest(TransactionType.EXPENSE, date, from.getId(), cost, null, null, null,
                    category, description, null, List.of()), TransactionSource.LOAN, ref(loan, number, "i"));
        }
        return view(userId, loan, true);
    }

    @Transactional
    public LoanView unpay(long userId, long id, int number) {
        Loan loan = require(userId, id);
        installment(loan, number);
        List<Transaction> recorded = transactions.findByUserIdAndExternalRefStartingWith(userId, "loan:" + loan.getId() + ":" + number + ":");
        if (recorded.isEmpty()) {
            throw ApiException.conflict("loan.notPaid");
        }
        recorded.forEach(tx -> transactionService.delete(userId, tx.getId()));
        return view(userId, loan, true);
    }

    /** Unpaid installments of all loans due up to {@code until} (overdue ones included). */
    @Transactional(readOnly = true)
    public List<DueInstallment> due(long userId, LocalDate until) {
        List<DueInstallment> result = new ArrayList<>();
        for (Loan loan : loans.findByUserIdOrderByIdAsc(userId)) {
            Map<Integer, Payment> paid = payments(userId, loan);
            String name = accounts.require(userId, loan.getAccountId()).getName();
            for (LoanInstallment i : installments.findByLoanIdOrderByNumberAsc(loan.getId())) {
                if (i.getDueDate().isAfter(until)) {
                    break;
                }
                if (i.getNumber() > loan.getPaidBefore() && !paid.containsKey(i.getNumber())) {
                    result.add(new DueInstallment(loan.getId(), name, loan.getAccountId(), loan.getPaymentAccountId(), i.getNumber(),
                            i.getDueDate(), i.getAmount(), i.getPrincipal(), i.getInterest()));
                }
            }
        }
        result.sort(Comparator.comparing(DueInstallment::dueDate));
        return result;
    }

    private LoanView view(long userId, Loan loan, boolean withInstallments) {
        LocalDate today = LocalDate.now(clock);
        Account account = accounts.require(userId, loan.getAccountId());
        Map<Integer, Payment> paid = payments(userId, loan);
        List<LoanInstallment> list = installments.findByLoanIdOrderByNumberAsc(loan.getId());
        List<InstallmentView> views = new ArrayList<>(list.size());
        BigDecimal balance = loan.getPrincipal();
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal remainingInterest = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        int paidCount = 0;
        int overdueCount = 0;
        InstallmentView next = null;
        for (LoanInstallment i : list) {
            balance = balance.subtract(i.getPrincipal());
            totalInterest = totalInterest.add(i.getInterest());
            Payment payment = paid.get(i.getNumber());
            InstallmentStatus status;
            if (i.getNumber() <= loan.getPaidBefore()) {
                status = InstallmentStatus.PAID_BEFORE;
            } else if (payment != null) {
                status = InstallmentStatus.PAID;
            } else if (i.getDueDate().isBefore(today)) {
                status = InstallmentStatus.OVERDUE;
            } else if (!i.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS))) {
                status = InstallmentStatus.DUE_SOON;
            } else {
                status = InstallmentStatus.UPCOMING;
            }
            boolean settled = status == InstallmentStatus.PAID || status == InstallmentStatus.PAID_BEFORE;
            if (settled) {
                paidCount++;
            } else {
                remainingInterest = remainingInterest.add(i.getInterest());
            }
            if (status == InstallmentStatus.OVERDUE) {
                overdueCount++;
                overdueAmount = overdueAmount.add(i.getAmount());
            }
            InstallmentView v = new InstallmentView(i.getNumber(), i.getDueDate(), i.getAmount(), i.getPrincipal(), i.getInterest(),
                    balance, status, payment == null ? null : payment.date(), payment == null ? null : payment.amount());
            if (next == null && !settled) {
                next = v;
            }
            views.add(v);
        }
        BigDecimal outstanding = balances.balance(userId, account.getId(), today).negate();
        return new LoanView(loan.getId(), account.getId(), account.getName(), account.getBank(), account.getCounterparty(),
                loan.getPrincipal(), loan.getAnnualRate(), loan.getTermMonths(), loan.getFirstDueDate(), loan.getMethod(),
                loan.getInstallmentAmount(), loan.getPaidBefore(), loan.getPaymentAccountId(), loan.getNotes(), outstanding, totalInterest,
                remainingInterest, paidCount, overdueCount, overdueAmount, next, list.isEmpty() ? null : list.getLast().getDueDate(),
                withInstallments ? views : null);
    }

    /** Payments recorded in the ledger, by installment number. */
    private Map<Integer, Payment> payments(long userId, Loan loan) {
        String prefix = "loan:" + loan.getId() + ":";
        Map<Integer, Payment> result = new HashMap<>();
        for (Transaction tx : transactions.findByUserIdAndExternalRefStartingWith(userId, prefix)) {
            String[] parts = tx.getExternalRef().substring(prefix.length()).split(":");
            if (parts.length != 2) {
                continue; // the disbursement
            }
            int number = Integer.parseInt(parts[0]);
            result.merge(number, new Payment(tx.getOccurredOn(), tx.getAmount()),
                    (a, b) -> new Payment(a.date().isAfter(b.date()) ? a.date() : b.date(), a.amount().add(b.amount())));
        }
        return result;
    }

    private void validateTerms(BigDecimal principal, BigDecimal annualRate, Integer termMonths, LocalDate firstDue, BigDecimal installment) {
        if (principal == null || principal.signum() <= 0 || principal.compareTo(MAX_AMOUNT) >= 0 || principal.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("loan.invalidPrincipal");
        }
        if (annualRate == null || annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.valueOf(100)) > 0 || annualRate.stripTrailingZeros().scale() > 4) {
            throw ApiException.badRequest("loan.invalidRate");
        }
        if (termMonths == null || termMonths < 1 || termMonths > 600) {
            throw ApiException.badRequest("loan.invalidTerm");
        }
        LocalDate today = LocalDate.now(clock);
        if (firstDue == null || firstDue.isBefore(today.minusYears(60)) || firstDue.isAfter(today.plusYears(10))) {
            throw ApiException.badRequest("loan.invalidDate");
        }
        if (installment != null && (installment.signum() <= 0 || installment.compareTo(MAX_AMOUNT) >= 0)) {
            throw ApiException.badRequest("loan.installmentTooSmall");
        }
    }

    private static void applyTerms(Loan loan, LoanRequest r, LoanMethod method, int paidBefore) {
        loan.setPrincipal(r.principal());
        loan.setAnnualRate(r.annualRate());
        loan.setTermMonths(r.termMonths());
        loan.setFirstDueDate(r.firstDueDate());
        loan.setMethod(method);
        loan.setInstallmentAmount(r.installmentAmount());
        loan.setPaidBefore(paidBefore);
    }

    private void saveSchedule(Loan loan, List<LoanSchedule.Line> lines) {
        installments.saveAll(lines.stream()
                .map(l -> new LoanInstallment(loan.getId(), l.number(), l.dueDate(), l.amount(), l.principal(), l.interest()))
                .toList());
    }

    private Long paymentAccount(long userId, Long accountId) {
        if (accountId == null) {
            return null;
        }
        Account account = tomanAccount(userId, accountId, "loan.paymentAccountToman");
        if (account.getType().isLiability()) {
            throw ApiException.badRequest("loan.paymentAccountToman");
        }
        return account.getId();
    }

    private Account tomanAccount(long userId, long accountId, String error) {
        Account account = accounts.require(userId, accountId);
        if (!commodities.toman().getId().equals(account.getCommodityId())) {
            throw ApiException.badRequest(error);
        }
        return account;
    }

    private Loan require(long userId, long id) {
        return loans.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("loan.notFound"));
    }

    private LoanInstallment installment(Loan loan, int number) {
        return installments.findByLoanIdOrderByNumberAsc(loan.getId()).stream().filter(i -> i.getNumber() == number).findFirst()
                .orElseThrow(() -> ApiException.notFound("loan.installmentNotFound"));
    }

    private static String notes(String notes) {
        String clean = PersianText.clean(notes);
        if (clean != null && clean.length() > 2000) {
            throw ApiException.badRequest("loan.invalidNotes");
        }
        return clean;
    }

    static String ref(Loan loan, int number, String part) {
        return "loan:" + loan.getId() + ":" + number + ":" + part;
    }

    static String disbursementRef(Loan loan) {
        return "loan:" + loan.getId() + ":disburse";
    }
}
