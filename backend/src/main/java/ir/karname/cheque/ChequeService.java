package ir.karname.cheque;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.Bank;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Issued and received cheques. Clearing one records its transaction ({@code cheque:{id}}): an
 * expense or income in Toman, or a transfer against the debt/receivable account it settles.
 * Moving a cleared cheque to another status removes that transaction again.
 */
@Service
public class ChequeService {

    private static final Pattern SAYAD = Pattern.compile("^[0-9]{16}$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");

    private final ChequeRepository cheques;
    private final AccountService accounts;
    private final CommodityService commodities;
    private final CategoryService categories;
    private final TransactionService transactionService;
    private final TransactionRepository transactions;
    private final Clock clock;

    public ChequeService(ChequeRepository cheques, AccountService accounts, CommodityService commodities, CategoryService categories,
            TransactionService transactionService, TransactionRepository transactions, Clock clock) {
        this.cheques = cheques;
        this.accounts = accounts;
        this.commodities = commodities;
        this.categories = categories;
        this.transactionService = transactionService;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record ChequeRequest(ChequeDirection direction, String sayadId, String serial, Bank bank, Long accountId, Long counterAccountId,
            Long categoryId, String counterparty, BigDecimal amount, LocalDate issueDate, LocalDate dueDate, String description, String notes) {
    }

    /** {@code date} and {@code accountId} are used when clearing. */
    public record StatusRequest(ChequeStatus status, LocalDate date, Long accountId) {
    }

    public record ChequeView(long id, ChequeDirection direction, ChequeStatus status, String sayadId, String serial, Bank bank, Long accountId,
            Long counterAccountId, Long categoryId, String counterparty, BigDecimal amount, LocalDate issueDate, LocalDate dueDate,
            LocalDate settledOn, String description, String notes, Long transactionId, boolean overdue, long daysToDue) {
    }

    @Transactional(readOnly = true)
    public List<ChequeView> list(long userId) {
        return cheques.findByUserIdOrderByDueDateAscIdAsc(userId).stream().map(c -> view(userId, c)).toList();
    }

    @Transactional(readOnly = true)
    public ChequeView get(long userId, long id) {
        return view(userId, require(userId, id));
    }

    /** Pending cheques due up to {@code until}, for reminders and the cash-flow forecast. */
    @Transactional(readOnly = true)
    public List<ChequeView> pendingUntil(long userId, LocalDate until) {
        return cheques.findByUserIdAndStatusAndDueDateLessThanEqualOrderByDueDateAsc(userId, ChequeStatus.PENDING, until).stream()
                .map(c -> view(userId, c)).toList();
    }

    @Transactional
    public ChequeView create(long userId, ChequeRequest request) {
        Cheque cheque = new Cheque(userId);
        apply(userId, cheque, request);
        cheques.saveAndFlush(cheque);
        return view(userId, cheque);
    }

    @Transactional
    public ChequeView update(long userId, long id, ChequeRequest request) {
        Cheque cheque = require(userId, id);
        apply(userId, cheque, request);
        if (cheque.getStatus() == ChequeStatus.CLEARED) {
            // keep the recorded transaction in line with the edited cheque
            removeTransaction(userId, cheque);
            record(userId, cheque, cheque.getSettledOn(), cheque.getAccountId());
        }
        return view(userId, cheque);
    }

    /** Deletes the cheque together with the transaction of its clearing, if any. */
    @Transactional
    public void delete(long userId, long id) {
        Cheque cheque = require(userId, id);
        removeTransaction(userId, cheque);
        cheques.delete(cheque);
    }

    @Transactional
    public ChequeView changeStatus(long userId, long id, StatusRequest request) {
        Cheque cheque = require(userId, id);
        ChequeStatus target = request.status();
        if (target == null) {
            throw ApiException.badRequest("cheque.invalidStatus");
        }
        if (target == cheque.getStatus()) {
            return view(userId, cheque);
        }
        removeTransaction(userId, cheque);
        cheque.setSettledOn(null);
        if (target == ChequeStatus.CLEARED) {
            if (cheque.getStatus() == ChequeStatus.CANCELLED) {
                throw ApiException.conflict("cheque.cancelled");
            }
            Long accountId = request.accountId() != null ? request.accountId() : cheque.getAccountId();
            if (accountId == null) {
                throw ApiException.badRequest("cheque.accountRequired");
            }
            cheque.setAccountId(tomanAsset(userId, accountId).getId());
            LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
            record(userId, cheque, date, cheque.getAccountId());
            cheque.setSettledOn(date);
        } else if (target == ChequeStatus.BOUNCED || target == ChequeStatus.CANCELLED) {
            cheque.setSettledOn(request.date() != null ? request.date() : LocalDate.now(clock));
        }
        cheque.setStatus(target);
        return view(userId, cheque);
    }

    private void record(long userId, Cheque cheque, LocalDate date, Long accountId) {
        String label = cheque.getDescription() != null ? cheque.getDescription() : label(cheque);
        TransactionRequest request;
        if (cheque.getDirection() == ChequeDirection.ISSUED) {
            request = cheque.getCounterAccountId() != null
                    ? new TransactionRequest(TransactionType.TRANSFER, date, accountId, cheque.getAmount(), cheque.getCounterAccountId(), null, null,
                    null, label, null, List.of())
                    : new TransactionRequest(TransactionType.EXPENSE, date, accountId, cheque.getAmount(), null, null, null, cheque.getCategoryId(),
                    label, null, List.of());
        } else {
            request = cheque.getCounterAccountId() != null
                    ? new TransactionRequest(TransactionType.TRANSFER, date, cheque.getCounterAccountId(), cheque.getAmount(), accountId, null, null,
                    null, label, null, List.of())
                    : new TransactionRequest(TransactionType.INCOME, date, accountId, cheque.getAmount(), null, null, null, cheque.getCategoryId(),
                    label, null, List.of());
        }
        transactionService.create(userId, request, TransactionSource.CHEQUE, ref(cheque));
    }

    private void removeTransaction(long userId, Cheque cheque) {
        transactions.findByUserIdAndExternalRef(userId, ref(cheque)).ifPresent(tx -> transactionService.delete(userId, tx.getId()));
    }

    private ChequeView view(long userId, Cheque c) {
        LocalDate today = LocalDate.now(clock);
        Long txId = c.getStatus() == ChequeStatus.CLEARED
                ? transactions.findByUserIdAndExternalRef(userId, ref(c)).map(Transaction::getId).orElse(null) : null;
        boolean overdue = c.getStatus() == ChequeStatus.PENDING && c.getDueDate().isBefore(today);
        return new ChequeView(c.getId(), c.getDirection(), c.getStatus(), c.getSayadId(), c.getSerial(), c.getBank(), c.getAccountId(),
                c.getCounterAccountId(), c.getCategoryId(), c.getCounterparty(), c.getAmount(), c.getIssueDate(), c.getDueDate(), c.getSettledOn(),
                c.getDescription(), c.getNotes(), txId, overdue, ChronoUnit.DAYS.between(today, c.getDueDate()));
    }

    private void apply(long userId, Cheque cheque, ChequeRequest r) {
        if (r.direction() == null) {
            throw ApiException.badRequest("cheque.directionRequired");
        }
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().compareTo(MAX_AMOUNT) >= 0 || r.amount().stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("cheque.invalidAmount");
        }
        if (r.dueDate() == null) {
            throw ApiException.badRequest("cheque.dueDateRequired");
        }
        String sayad = r.sayadId() == null || r.sayadId().isBlank() ? null : PersianText.normalizeDigits(r.sayadId()).replaceAll("[\\s-]", "");
        if (sayad != null && !SAYAD.matcher(sayad).matches()) {
            throw ApiException.badRequest("cheque.invalidSayad");
        }
        String serial = PersianText.clean(r.serial());
        String counterparty = PersianText.clean(r.counterparty());
        String description = PersianText.clean(r.description());
        String notes = PersianText.clean(r.notes());
        if ((serial != null && serial.length() > 30) || (counterparty != null && counterparty.length() > 100)
                || (description != null && description.length() > 300) || (notes != null && notes.length() > 2000)) {
            throw ApiException.badRequest("cheque.textTooLong");
        }
        Long accountId = r.accountId() == null ? null : tomanAsset(userId, r.accountId()).getId();
        Long counterAccountId = null;
        if (r.counterAccountId() != null) {
            Account counter = accounts.require(userId, r.counterAccountId());
            if (!commodities.toman().getId().equals(counter.getCommodityId()) || counter.getId().equals(accountId)) {
                throw ApiException.badRequest("cheque.invalidCounterAccount");
            }
            counterAccountId = counter.getId();
        }
        Long categoryId = null;
        if (r.categoryId() != null && counterAccountId == null) {
            CategoryKind kind = r.direction() == ChequeDirection.ISSUED ? CategoryKind.EXPENSE : CategoryKind.INCOME;
            categoryId = categories.requireForKind(userId, r.categoryId(), kind).getId();
        }
        cheque.setDirection(r.direction());
        cheque.setSayadId(sayad);
        cheque.setSerial(serial);
        cheque.setBank(r.bank());
        cheque.setAccountId(accountId);
        cheque.setCounterAccountId(counterAccountId);
        cheque.setCategoryId(categoryId);
        cheque.setCounterparty(counterparty);
        cheque.setAmount(r.amount());
        cheque.setIssueDate(r.issueDate());
        cheque.setDueDate(r.dueDate());
        cheque.setDescription(description);
        cheque.setNotes(notes);
    }

    private Account tomanAsset(long userId, long accountId) {
        Account account = accounts.require(userId, accountId);
        if (!commodities.toman().getId().equals(account.getCommodityId()) || account.getType().isLiability()) {
            throw ApiException.badRequest("cheque.accountToman");
        }
        return account;
    }

    private static String label(Cheque c) {
        String who = c.getCounterparty() != null ? " " + (c.getDirection() == ChequeDirection.ISSUED ? "به " : "از ") + c.getCounterparty() : "";
        return (c.getDirection() == ChequeDirection.ISSUED ? "چک صادره" : "چک دریافتی") + who;
    }

    static String ref(Cheque cheque) {
        return "cheque:" + cheque.getId();
    }

    private Cheque require(long userId, long id) {
        return cheques.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("cheque.notFound"));
    }
}
