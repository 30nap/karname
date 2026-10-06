package ir.karname.transaction;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountType;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TransactionViews {

    private final AccountService accounts;
    private final CategoryService categories;
    private final CommodityRepository commodities;

    public TransactionViews(AccountService accounts, CategoryService categories, CommodityRepository commodities) {
        this.accounts = accounts;
        this.categories = categories;
        this.commodities = commodities;
    }

    public record AccountRef(long id, String name, String commodity, AccountType type) {
    }

    public record CategoryRef(long id, String name, String icon, CategoryKind kind, Long parentId, String parentName) {
    }

    public record TransactionView(long id, TransactionType type, LocalDate date, AccountRef account, BigDecimal amount,
            AccountRef toAccount, BigDecimal toAmount, BigDecimal fee, CategoryRef category, String description,
            String notes, List<String> tags, TransactionSource source, Instant createdAt) {
    }

    /** Lookup context for rendering many transactions of one user without N+1 queries. */
    public final class Context {

        private final Map<Long, Account> accountsById;
        private final Map<Long, Category> categoriesById;
        private final Map<Long, Commodity> commoditiesById;

        private Context(long userId) {
            List<Account> userAccounts = accounts.list(userId);
            this.accountsById = userAccounts.stream().collect(Collectors.toMap(Account::getId, Function.identity()));
            this.categoriesById = categories.list(userId).stream().collect(Collectors.toMap(Category::getId, Function.identity()));
            this.commoditiesById = commodities.findAllById(userAccounts.stream().map(Account::getCommodityId).distinct().toList())
                    .stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
        }

        public TransactionView view(Transaction t) {
            return new TransactionView(t.getId(), t.getType(), t.getOccurredOn(), account(t.getAccountId()), t.getAmount(),
                    t.getToAccountId() == null ? null : account(t.getToAccountId()), t.getToAmount(), t.getFee(),
                    category(t.getCategoryId()), t.getDescription(), t.getNotes(), t.getTags(), t.getSource(), t.getCreatedAt());
        }

        public AccountRef account(Long id) {
            Account a = accountsById.get(id);
            if (a == null) {
                return null;
            }
            Commodity c = commoditiesById.get(a.getCommodityId());
            return new AccountRef(a.getId(), a.getName(), c == null ? null : c.getCode(), a.getType());
        }

        public CategoryRef category(Long id) {
            if (id == null) {
                return null;
            }
            Category c = categoriesById.get(id);
            if (c == null) {
                return null;
            }
            Category parent = c.getParentId() == null ? null : categoriesById.get(c.getParentId());
            String icon = c.getIcon() != null ? c.getIcon() : parent != null ? parent.getIcon() : null;
            return new CategoryRef(c.getId(), c.getName(), icon, c.getKind(), c.getParentId(), parent == null ? null : parent.getName());
        }

        public Commodity commodityOfAccount(long accountId) {
            Account a = accountsById.get(accountId);
            return a == null ? null : commoditiesById.get(a.getCommodityId());
        }
    }

    @Transactional(readOnly = true)
    public Context context(long userId) {
        return new Context(userId);
    }

    @Transactional(readOnly = true)
    public TransactionView view(long userId, Transaction t) {
        return context(userId).view(t);
    }

    @Transactional(readOnly = true)
    public List<TransactionView> views(long userId, Collection<Transaction> list) {
        Context ctx = context(userId);
        return list.stream().map(ctx::view).toList();
    }
}
