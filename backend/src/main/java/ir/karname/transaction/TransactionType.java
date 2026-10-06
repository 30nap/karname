package ir.karname.transaction;

public enum TransactionType {
    INCOME,
    EXPENSE,
    TRANSFER,
    /** Starting balance of an account (signed). */
    OPENING,
    /** Correction to match a real balance (signed); excluded from income/expense reports. */
    ADJUSTMENT
}
