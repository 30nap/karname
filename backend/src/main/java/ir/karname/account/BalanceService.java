package ir.karname.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Account balances, always derived from transactions (the ledger_postings view). */
@Service
public class BalanceService {

    private final JdbcClient jdbc;

    public BalanceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Balance of every account of the user at the end of {@code asOf} (accounts without postings are absent). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> balances(long userId, LocalDate asOf) {
        Map<Long, BigDecimal> result = new HashMap<>();
        jdbc.sql("""
                SELECT account_id, SUM(delta) FROM ledger_postings
                WHERE user_id = :userId AND occurred_on <= :asOf
                GROUP BY account_id
                """)
                .param("userId", userId)
                .param("asOf", asOf)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(long userId, long accountId, LocalDate asOf) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(delta), 0) FROM ledger_postings
                WHERE user_id = :userId AND account_id = :accountId AND occurred_on <= :asOf
                """)
                .param("userId", userId)
                .param("accountId", accountId)
                .param("asOf", asOf)
                .query(BigDecimal.class)
                .single();
    }

    @Transactional(readOnly = true)
    public long transactionCount(long accountId) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE account_id = :id OR to_account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }
}
