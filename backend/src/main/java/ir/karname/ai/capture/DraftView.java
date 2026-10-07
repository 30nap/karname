package ir.karname.ai.capture;

import ir.karname.ai.capture.DraftBuilder.Confidence;
import ir.karname.ai.capture.DraftBuilder.Draft;
import ir.karname.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A draft as the user sees it, and whether it has been recorded since. */
public record DraftView(String ref, TransactionType type, LocalDate date, Long accountId, BigDecimal amount, Long toAccountId,
        BigDecimal toAmount, Long categoryId, String description, Confidence confidence, List<String> warnings, boolean duplicate,
        boolean recorded) {

    public static DraftView of(Draft d, boolean recorded) {
        return new DraftView(d.ref(), d.type(), d.date(), d.accountId(), d.amount(), d.toAccountId(), d.toAmount(), d.categoryId(),
                d.description(), d.confidence(), d.warnings(), d.duplicate(), recorded);
    }
}
