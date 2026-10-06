package ir.karname.ledger;

import ir.karname.common.jalali.JalaliMonth;
import ir.karname.ledger.NetWorthService.NetWorthView;
import ir.karname.transaction.TransactionQueryService;
import ir.karname.transaction.TransactionQueryService.Filter;
import ir.karname.transaction.TransactionQueryService.TransactionPage;
import ir.karname.transaction.TransactionType;
import ir.karname.transaction.TransactionViews.TransactionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Everything the dashboard needs in one request. */
@Service
public class DashboardService {

    private final NetWorthService netWorth;
    private final TransactionQueryService transactions;
    private final Clock clock;

    public DashboardService(NetWorthService netWorth, TransactionQueryService transactions, Clock clock) {
        this.netWorth = netWorth;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record MonthSummary(String month, BigDecimal incomeToman, BigDecimal expenseToman, BigDecimal netToman,
            BigDecimal savingsRate, int unpricedCount) {
    }

    public record DashboardView(NetWorthView netWorth, MonthSummary currentMonth, MonthSummary previousMonth,
            List<TransactionView> recentTransactions) {
    }

    @Transactional(readOnly = true)
    public DashboardView dashboard(long userId) {
        LocalDate today = LocalDate.now(clock);
        JalaliMonth month = JalaliMonth.from(today);
        TransactionPage recent = transactions.search(userId, new Filter(null, null,
                List.of(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.TRANSFER), null, null, false, null, null, null, null), 0, 6);
        return new DashboardView(netWorth.current(userId), summary(userId, month, today), summary(userId, month.previous(), null),
                recent.items());
    }

    public MonthSummary summary(long userId, JalaliMonth month, LocalDate until) {
        LocalDate end = until != null && until.isBefore(month.endDate()) ? until : month.endDate();
        TransactionPage page = transactions.search(userId, Filter.between(month.startDate(), end), 0, 1);
        BigDecimal net = page.incomeToman().subtract(page.expenseToman());
        BigDecimal rate = page.incomeToman().signum() > 0 ? net.divide(page.incomeToman(), 4, RoundingMode.HALF_EVEN) : null;
        return new MonthSummary(month.toString(), page.incomeToman(), page.expenseToman(), net, rate, page.unpricedCount());
    }
}
