package ir.karname.support;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.category.Category;
import ir.karname.category.CategoryService;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PriceService;
import ir.karname.transaction.Transaction;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionType;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Shortcuts to set up financial data through the services. */
public abstract class FinanceTestSupport extends AbstractIntegrationTest {

    /** 14 Mehr 1405, the default "today" of the test clock. */
    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected TransactionService transactionService;

    @Autowired
    protected CategoryService categoryService;

    @Autowired
    protected CommodityService commodityService;

    @Autowired
    protected PriceService priceService;

    protected Account account(TestUser user, String name, AccountType type, String commodity, String opening) {
        return accountService.create(user.id(), new AccountRequest(name, type, commodity, null, null, null, null, null, null,
                opening == null ? null : new BigDecimal(opening), TODAY.minusMonths(6)));
    }

    protected Account bank(TestUser user, String name, String opening) {
        return account(user, name, AccountType.BANK, "IRT", opening);
    }

    protected long category(TestUser user, String name) {
        return categoryService.list(user.id()).stream().filter(c -> c.getName().equals(name)).map(Category::getId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No category " + name));
    }

    protected Transaction expense(TestUser user, Account account, String amount, LocalDate date, Long categoryId, String description) {
        return transactionService.create(user.id(), new TransactionRequest(TransactionType.EXPENSE, date, account.getId(),
                new BigDecimal(amount), null, null, null, categoryId, description, null, List.of()));
    }

    protected Transaction income(TestUser user, Account account, String amount, LocalDate date, Long categoryId, String description) {
        return transactionService.create(user.id(), new TransactionRequest(TransactionType.INCOME, date, account.getId(),
                new BigDecimal(amount), null, null, null, categoryId, description, null, List.of()));
    }

    protected Transaction transfer(TestUser user, Account from, Account to, String amount, String toAmount, LocalDate date) {
        return transactionService.create(user.id(), new TransactionRequest(TransactionType.TRANSFER, date, from.getId(),
                new BigDecimal(amount), to.getId(), toAmount == null ? null : new BigDecimal(toAmount), null, null, null, null, List.of()));
    }

    protected void globalPrice(String code, String priceToman, Instant at) {
        priceService.recordAutomatic(commodityService.require(0, code), new BigDecimal(priceToman), at, "TEST");
    }
}
