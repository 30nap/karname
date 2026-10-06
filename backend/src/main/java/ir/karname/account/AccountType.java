package ir.karname.account;

/** What an account represents. Liabilities (loans, debts) carry negative balances. */
public enum AccountType {
    CASH(false),
    BANK(false),
    EWALLET(false),
    CURRENCY(false),
    GOLD(false),
    CRYPTO(false),
    INVESTMENT(false),
    PROPERTY(false),
    VEHICLE(false),
    RECEIVABLE(false),
    OTHER_ASSET(false),
    LOAN(true),
    DEBT(true),
    CREDIT(true);

    private final boolean liability;

    AccountType(boolean liability) {
        this.liability = liability;
    }

    public boolean isLiability() {
        return liability;
    }
}
