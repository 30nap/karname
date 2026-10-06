package ir.karname.account;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published (synchronously, inside the creating transaction) when an account is opened with a
 * starting balance; the transaction module records it as an OPENING transaction.
 */
public record AccountCreatedEvent(long userId, long accountId, BigDecimal openingBalance, LocalDate openingDate) {
}
