package ir.karname.transaction;

import ir.karname.account.AccountCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Turns an account's opening balance into an OPENING transaction (same database transaction). */
@Component
class OpeningBalanceListener {

    private final TransactionService transactions;

    OpeningBalanceListener(TransactionService transactions) {
        this.transactions = transactions;
    }

    @EventListener
    void onAccountCreated(AccountCreatedEvent event) {
        transactions.createOpening(event.userId(), event.accountId(), event.openingBalance(), event.openingDate());
    }
}
