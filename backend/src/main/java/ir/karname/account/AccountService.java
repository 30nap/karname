package ir.karname.account;

import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityService;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class AccountService {

    private static final Pattern ICON = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern HINT = Pattern.compile("^\\d{2,8}$");

    private final AccountRepository accounts;
    private final CommodityService commodities;
    private final BalanceService balances;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CommodityService commodities, BalanceService balances,
            ApplicationEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.commodities = commodities;
        this.balances = balances;
        this.events = events;
        this.clock = clock;
    }

    public record AccountRequest(String name, AccountType type, String commodity, Bank bank, List<String> identifierHints,
            String counterparty, String icon, Boolean includeInNetWorth, String notes, BigDecimal openingBalance,
            LocalDate openingDate) {
    }

    @Transactional(readOnly = true)
    public Account require(long userId, long accountId) {
        return accounts.findByIdAndUserId(accountId, userId).orElseThrow(() -> ApiException.notFound("account.notFound"));
    }

    @Transactional(readOnly = true)
    public List<Account> list(long userId) {
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    /**
     * Opens an account. For liabilities the opening balance is entered as the (positive) amount
     * owed and stored as a negative balance.
     */
    @Transactional
    public Account create(long userId, AccountRequest request) {
        if (request.type() == null) {
            throw ApiException.badRequest("account.typeRequired");
        }
        Commodity commodity = commodities.require(userId, request.commodity());
        Account account = new Account(userId, cleanName(request.name()), request.type(), commodity.getId());
        apply(account, request);
        accounts.save(account);

        BigDecimal opening = request.openingBalance();
        if (opening != null && opening.signum() != 0) {
            if (opening.abs().compareTo(new BigDecimal("1e18")) >= 0) {
                throw ApiException.badRequest("transaction.invalidAmount");
            }
            BigDecimal signed = request.type().isLiability() ? opening.abs().negate() : opening;
            LocalDate date = request.openingDate() != null ? request.openingDate() : LocalDate.now(clock);
            events.publishEvent(new AccountCreatedEvent(userId, account.getId(), signed, date));
        }
        return account;
    }

    @Transactional
    public Account update(long userId, long accountId, AccountRequest request) {
        Account account = require(userId, accountId);
        account.setName(cleanName(request.name()));
        if (request.type() != null && request.type() != account.getType()) {
            boolean signChange = request.type().isLiability() != account.getType().isLiability();
            if (signChange && balances.transactionCount(accountId) > 0) {
                throw ApiException.conflict("account.typeChangeNotAllowed");
            }
            account.setType(request.type());
        }
        if (request.commodity() != null) {
            Commodity commodity = commodities.require(userId, request.commodity());
            if (!commodity.getId().equals(account.getCommodityId())) {
                throw ApiException.conflict("account.commodityChangeNotAllowed");
            }
        }
        apply(account, request);
        return account;
    }

    @Transactional
    public Account setArchived(long userId, long accountId, boolean archived) {
        Account account = require(userId, accountId);
        account.setArchived(archived);
        return account;
    }

    @Transactional
    public void delete(long userId, long accountId, boolean force) {
        Account account = require(userId, accountId);
        long count = balances.transactionCount(accountId);
        if (count > 0 && !force) {
            throw ApiException.conflict("account.hasTransactions", count);
        }
        accounts.delete(account);
    }

    private void apply(Account account, AccountRequest request) {
        account.setBank(request.bank());
        account.setIdentifierHints(cleanHints(request.identifierHints()));
        String counterparty = PersianText.clean(request.counterparty());
        if (counterparty != null && counterparty.length() > 100) {
            throw ApiException.badRequest("account.invalidCounterparty");
        }
        account.setCounterparty(counterparty);
        String icon = request.icon() == null || request.icon().isBlank() ? null : request.icon().trim();
        if (icon != null && !ICON.matcher(icon).matches()) {
            throw ApiException.badRequest("account.invalidIcon");
        }
        account.setIcon(icon);
        if (request.includeInNetWorth() != null) {
            account.setIncludeInNetWorth(request.includeInNetWorth());
        }
        String notes = request.notes() == null ? null : request.notes().trim();
        if (notes != null && notes.length() > 2000) {
            throw ApiException.badRequest("account.invalidNotes");
        }
        account.setNotes(notes == null || notes.isEmpty() ? null : notes);
    }

    private static String cleanName(String name) {
        String clean = PersianText.clean(name);
        if (clean == null || clean.length() > 100) {
            throw ApiException.badRequest("account.invalidName");
        }
        return clean;
    }

    private static List<String> cleanHints(List<String> hints) {
        if (hints == null) {
            return List.of();
        }
        List<String> clean = hints.stream()
                .map(PersianText::normalizeDigits)
                .map(h -> h == null ? "" : h.replaceAll("[\\s*\\-]", ""))
                .filter(h -> !h.isEmpty())
                .distinct()
                .toList();
        if (clean.size() > 5 || clean.stream().anyMatch(h -> !HINT.matcher(h).matches())) {
            throw ApiException.badRequest("account.invalidHints");
        }
        return clean;
    }
}
