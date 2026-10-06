package ir.karname.transaction;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.BalanceService;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.category.MerchantRuleService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.PriceService;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Creates, edits and deletes transactions, enforcing the ledger rules. */
@Service
public class TransactionService {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");
    private static final LocalDate MIN_DATE = LocalDate.of(1921, 3, 21); // 1300/01/01

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final BalanceService balances;
    private final CategoryService categories;
    private final MerchantRuleService merchantRules;
    private final CommodityRepository commodities;
    private final PriceService prices;
    private final KarnameProperties properties;
    private final Clock clock;

    public TransactionService(TransactionRepository transactions, AccountService accounts, BalanceService balances,
            CategoryService categories, MerchantRuleService merchantRules, CommodityRepository commodities,
            PriceService prices, KarnameProperties properties, Clock clock) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.balances = balances;
        this.categories = categories;
        this.merchantRules = merchantRules;
        this.commodities = commodities;
        this.prices = prices;
        this.properties = properties;
        this.clock = clock;
    }

    public record TransactionRequest(TransactionType type, LocalDate date, Long accountId, BigDecimal amount,
            Long toAccountId, BigDecimal toAmount, BigDecimal fee, Long categoryId, String description, String notes,
            List<String> tags) {
    }

    @Transactional(readOnly = true)
    public Transaction require(long userId, long id) {
        return transactions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("transaction.notFound"));
    }

    @Transactional
    public Transaction create(long userId, TransactionRequest request) {
        return create(userId, request, TransactionSource.MANUAL, null);
    }

    @Transactional
    public Transaction create(long userId, TransactionRequest request, TransactionSource source, String externalRef) {
        if (externalRef != null && transactions.existsByUserIdAndExternalRef(userId, externalRef)) {
            throw ApiException.conflict("transaction.duplicate");
        }
        Transaction tx = new Transaction(userId, requireUserType(request.type()));
        tx.setSource(source);
        tx.setExternalRef(externalRef);
        apply(userId, tx, request, true);
        transactions.save(tx);
        afterSave(userId, tx);
        return tx;
    }

    @Transactional
    public Transaction update(long userId, long id, TransactionRequest request) {
        Transaction tx = require(userId, id);
        if (tx.getType() == TransactionType.OPENING || tx.getType() == TransactionType.ADJUSTMENT) {
            return updateBalanceEntry(userId, tx, request);
        }
        tx.setType(requireUserType(request.type()));
        boolean accountChanged = !tx.getAccountId().equals(request.accountId())
                || (request.toAccountId() != null && !request.toAccountId().equals(tx.getToAccountId()));
        apply(userId, tx, request, accountChanged);
        prices.deleteForTransaction(tx.getId());
        transactions.flush();
        afterSave(userId, tx);
        return tx;
    }

    @Transactional
    public void delete(long userId, long id) {
        transactions.delete(require(userId, id));
    }

    /** Records an account's starting balance (signed). */
    @Transactional
    public Transaction createOpening(long userId, long accountId, BigDecimal signedAmount, LocalDate date) {
        Account account = accounts.require(userId, accountId);
        Transaction tx = new Transaction(userId, TransactionType.OPENING);
        tx.setAccountId(account.getId());
        tx.setAmount(scaled(signedAmount));
        tx.setOccurredOn(checkDate(date));
        tx.setDescription("مانده‌ی اول دوره");
        tx.setSearchText(PersianText.normalizeForSearch(tx.getDescription()));
        tx.setSource(TransactionSource.SYSTEM);
        return transactions.save(tx);
    }

    /**
     * Matches an account to its real balance on a date by recording the difference as an
     * ADJUSTMENT (for liabilities the real balance is entered as the positive amount owed).
     */
    @Transactional
    public Transaction reconcile(long userId, long accountId, BigDecimal actualBalance, LocalDate date) {
        Account account = accounts.require(userId, accountId);
        LocalDate day = checkDate(date == null ? LocalDate.now(clock) : date);
        if (actualBalance == null || actualBalance.abs().compareTo(MAX_AMOUNT) >= 0) {
            throw ApiException.badRequest("transaction.invalidAmount");
        }
        BigDecimal target = account.getType().isLiability() ? actualBalance.abs().negate() : actualBalance;
        BigDecimal current = balances.balance(userId, accountId, day);
        BigDecimal difference = target.subtract(current);
        if (difference.signum() == 0) {
            throw ApiException.badRequest("transaction.alreadyReconciled");
        }
        Transaction tx = new Transaction(userId, TransactionType.ADJUSTMENT);
        tx.setAccountId(account.getId());
        tx.setAmount(scaled(difference));
        tx.setOccurredOn(day);
        tx.setDescription("تطبیق موجودی");
        tx.setSearchText(PersianText.normalizeForSearch(tx.getDescription()));
        tx.setSource(TransactionSource.SYSTEM);
        return transactions.save(tx);
    }

    private Transaction updateBalanceEntry(long userId, Transaction tx, TransactionRequest request) {
        if (request.amount() == null || request.amount().abs().compareTo(MAX_AMOUNT) >= 0) {
            throw ApiException.badRequest("transaction.invalidAmount");
        }
        Account account = accounts.require(userId, tx.getAccountId());
        BigDecimal amount = tx.getType() == TransactionType.OPENING && account.getType().isLiability()
                ? request.amount().abs().negate() : request.amount();
        tx.setAmount(scaled(amount));
        tx.setOccurredOn(checkDate(request.date()));
        tx.setNotes(cleanNotes(request.notes()));
        return tx;
    }

    private void apply(long userId, Transaction tx, TransactionRequest request, boolean checkArchived) {
        tx.setOccurredOn(checkDate(request.date()));
        if (request.accountId() == null) {
            throw ApiException.badRequest("transaction.accountRequired");
        }
        Account account = accounts.require(userId, request.accountId());
        if (checkArchived && account.isArchived()) {
            throw ApiException.badRequest("account.archived");
        }
        tx.setAccountId(account.getId());
        tx.setAmount(checkAmount(request.amount()));

        switch (tx.getType()) {
            case INCOME, EXPENSE -> {
                CategoryKind kind = tx.getType() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
                tx.setCategoryId(request.categoryId() == null ? null
                        : categories.requireForKind(userId, request.categoryId(), kind).getId());
                tx.setToAccountId(null);
                tx.setToAmount(null);
                tx.setFee(null);
            }
            case TRANSFER -> {
                if (request.toAccountId() == null) {
                    throw ApiException.badRequest("transaction.toAccountRequired");
                }
                Account to = accounts.require(userId, request.toAccountId());
                if (to.getId().equals(account.getId())) {
                    throw ApiException.badRequest("transaction.sameAccount");
                }
                if (checkArchived && to.isArchived()) {
                    throw ApiException.badRequest("account.archived");
                }
                tx.setToAccountId(to.getId());
                if (to.getCommodityId().equals(account.getCommodityId())) {
                    if (request.toAmount() != null && request.toAmount().compareTo(tx.getAmount()) != 0) {
                        throw ApiException.badRequest("transaction.transferAmountMismatch");
                    }
                    tx.setToAmount(tx.getAmount());
                } else {
                    if (request.toAmount() == null) {
                        throw ApiException.badRequest("transaction.toAmountRequired");
                    }
                    tx.setToAmount(checkAmount(request.toAmount()));
                }
                BigDecimal fee = request.fee();
                if (fee != null && (fee.signum() < 0 || fee.compareTo(MAX_AMOUNT) >= 0)) {
                    throw ApiException.badRequest("transaction.invalidFee");
                }
                tx.setFee(fee == null || fee.signum() == 0 ? null : scaled(fee));
                tx.setCategoryId(null);
            }
            default -> throw ApiException.badRequest("transaction.invalidType");
        }

        String description = PersianText.clean(request.description());
        if (description != null && description.length() > 300) {
            throw ApiException.badRequest("transaction.descriptionTooLong");
        }
        tx.setDescription(description);
        tx.setNotes(cleanNotes(request.notes()));
        List<String> tags = cleanTags(request.tags());
        tx.setTags(tags);
        tx.setSearchText(PersianText.normalizeForSearch(String.join(" ",
                description == null ? "" : description, tx.getNotes() == null ? "" : tx.getNotes(), String.join(" ", tags))));
    }

    private void afterSave(long userId, Transaction tx) {
        if (tx.getType() == TransactionType.TRANSFER) {
            recordImpliedPrice(userId, tx);
        }
        if ((tx.getType() == TransactionType.INCOME || tx.getType() == TransactionType.EXPENSE)
                && tx.getCategoryId() != null && tx.getDescription() != null) {
            merchantRules.learn(userId, tx.getDescription(), tx.getCategoryId());
        }
    }

    /** Buying gold/currency with Toman (or selling for Toman) reveals that commodity's price on that day. */
    private void recordImpliedPrice(long userId, Transaction tx) {
        Account from = accounts.require(userId, tx.getAccountId());
        Account to = accounts.require(userId, tx.getToAccountId());
        if (from.getCommodityId().equals(to.getCommodityId())) {
            return;
        }
        Commodity fromCommodity = commodities.findById(from.getCommodityId()).orElseThrow();
        Commodity toCommodity = commodities.findById(to.getCommodityId()).orElseThrow();
        Instant at = pricedAt(tx.getOccurredOn());
        if (fromCommodity.isToman() && !toCommodity.isToman()) {
            prices.recordImplied(userId, toCommodity.getId(), tx.getAmount().divide(tx.getToAmount(), 8, RoundingMode.HALF_EVEN), at, tx.getId());
        } else if (toCommodity.isToman() && !fromCommodity.isToman()) {
            prices.recordImplied(userId, fromCommodity.getId(), tx.getToAmount().divide(tx.getAmount(), 8, RoundingMode.HALF_EVEN), at, tx.getId());
        }
    }

    private Instant pricedAt(LocalDate date) {
        Instant now = clock.instant();
        if (date.equals(LocalDate.now(clock))) {
            return now;
        }
        return date.atTime(LocalTime.NOON).atZone(properties.timezone()).toInstant();
    }

    private static TransactionType requireUserType(TransactionType type) {
        if (type != TransactionType.INCOME && type != TransactionType.EXPENSE && type != TransactionType.TRANSFER) {
            throw ApiException.badRequest("transaction.invalidType");
        }
        return type;
    }

    private LocalDate checkDate(LocalDate date) {
        if (date == null) {
            throw ApiException.badRequest("transaction.dateRequired");
        }
        if (date.isBefore(MIN_DATE) || date.isAfter(LocalDate.now(clock).plusYears(1))) {
            throw ApiException.badRequest("transaction.dateOutOfRange");
        }
        return date;
    }

    private static BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) >= 0) {
            throw ApiException.badRequest("transaction.invalidAmount");
        }
        return scaled(amount);
    }

    static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(Math.min(Math.max(amount.scale(), 0), 8), RoundingMode.HALF_EVEN);
    }

    private static String cleanNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String trimmed = PersianText.normalize(notes).trim();
        if (trimmed.length() > 2000) {
            throw ApiException.badRequest("transaction.notesTooLong");
        }
        return trimmed;
    }

    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            String clean = PersianText.clean(tag);
            if (clean == null) {
                continue;
            }
            clean = clean.replace(",", " ").replace("#", "").trim();
            if (clean.isEmpty() || clean.length() > 30) {
                throw ApiException.badRequest("transaction.invalidTag");
            }
            if (!result.contains(clean)) {
                result.add(clean);
            }
        }
        if (result.size() > 10) {
            throw ApiException.badRequest("transaction.tooManyTags");
        }
        return result;
    }
}
