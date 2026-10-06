package ir.karname.cheque;

public enum ChequeDirection {
    /** Written by the user: money leaves their account on the due date. */
    ISSUED,
    /** Received from someone: money arrives when it is cleared. */
    RECEIVED
}
