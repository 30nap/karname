package ir.karname.loan;

public enum LoanMethod {
    /** Equal installments (Iranian banks' standard; Qard al-Hasan fees use it too). */
    ANNUITY,
    /** Equal principal parts with interest on the remaining balance (decreasing installments). */
    EQUAL_PRINCIPAL
}
