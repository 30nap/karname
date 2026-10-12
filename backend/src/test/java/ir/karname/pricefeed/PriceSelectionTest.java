package ir.karname.pricefeed;

import ir.karname.pricefeed.PriceSelection.Decision;
import ir.karname.pricefeed.PriceSelection.Quote;
import ir.karname.pricefeed.PriceSelection.Status;
import ir.karname.pricefeed.PriceSelection.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceSelectionTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    /** A source quoting {@code price}, {@code minutesAgo}, every 30 minutes. */
    private static Quote quote(long id, int priority, String price, int minutesAgo) {
        return new Quote(id, "s" + id, priority, true, 30, new BigDecimal(price), NOW.minus(Duration.ofMinutes(minutesAgo)));
    }

    private static List<Status> statuses(Decision d) {
        return d.verdicts().stream().map(Verdict::status).toList();
    }

    @Test
    void thePreferredFreshQuoteWinsAndTheOthersBackItUp() {
        Decision d = PriceSelection.decide(List.of(quote(2, 20, "104100", 3), quote(1, 10, "104000", 20)), NOW, null);
        assertThat(d.chosen().sourceId()).isEqualTo(1);
        assertThat(statuses(d)).containsExactly(Status.CHOSEN, Status.BACKUP);
        assertThat(d.warning()).isNull();
    }

    @Test
    void aStaleQuoteGivesWayToTheNextSource() {
        // 30-minute interval: fresh for 65 minutes
        Decision d = PriceSelection.decide(List.of(quote(1, 10, "104000", 66), quote(2, 20, "104300", 2)), NOW, null);
        assertThat(d.chosen().sourceId()).isEqualTo(2);
        assertThat(statuses(d)).containsExactly(Status.STALE, Status.CHOSEN);
        assertThat(PriceSelection.decide(List.of(quote(1, 10, "104000", 65)), NOW, null).chosen()).isNotNull();
    }

    @Test
    void aSwitchedOffSourceIsNeverUsed() {
        Quote off = new Quote(1, "off", 10, false, 30, new BigDecimal("104000"), NOW);
        Decision d = PriceSelection.decide(List.of(off), NOW, null);
        assertThat(d.chosen()).isNull();
        assertThat(statuses(d)).containsExactly(Status.OFF);
    }

    @Test
    void withThreeSourcesAQuoteFarFromTheMedianIsSetAside() {
        // the preferred source reads Rial as Toman: ten times the others
        Decision d = PriceSelection.decide(List.of(quote(1, 10, "1040000", 1), quote(2, 20, "104000", 1), quote(3, 30, "103800", 1)), NOW, null);
        assertThat(d.chosen().sourceId()).isEqualTo(2);
        assertThat(statuses(d)).containsExactly(Status.OUTLIER, Status.CHOSEN, Status.BACKUP);
        assertThat(d.warning()).contains("«s1»").contains("کنار گذاشته شد");
    }

    @Test
    void twoSourcesThatDisagreeAreSettledByTheLastRecordedPrice() {
        List<Quote> quotes = List.of(quote(1, 10, "1040000", 1), quote(2, 20, "104000", 1));
        Decision judged = PriceSelection.decide(quotes, NOW, new BigDecimal("103000"));
        assertThat(judged.chosen().sourceId()).isEqualTo(2);
        assertThat(judged.warning()).contains("اختلاف");
        // with nothing to compare to, preference decides, with a warning
        Decision blind = PriceSelection.decide(quotes, NOW, null);
        assertThat(blind.chosen().sourceId()).isEqualTo(1);
        assertThat(blind.warning()).contains("اختلاف");
    }

    @Test
    void smallDifferencesAreNormal() {
        Decision d = PriceSelection.decide(List.of(quote(1, 10, "104000", 1), quote(2, 20, "110000", 1), quote(3, 30, "99000", 1)), NOW,
                new BigDecimal("104500"));
        assertThat(d.chosen().sourceId()).isEqualTo(1);
        assertThat(d.warning()).isNull();
        assertThat(statuses(d)).containsExactly(Status.CHOSEN, Status.BACKUP, Status.BACKUP);
    }

    @Test
    void aBigJumpFromTheLastPriceIsFlaggedButRecorded() {
        Decision d = PriceSelection.decide(List.of(quote(1, 10, "170000", 1)), NOW, new BigDecimal("104000"));
        assertThat(d.chosen().sourceId()).isEqualTo(1);
        assertThat(d.warning()).contains("۵۰٪");
    }

    @Test
    void theMedianOfAnEvenCountIsTheMiddleAverage() {
        assertThat(PriceSelection.median(List.of(new BigDecimal("4"), new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("2"))))
                .isEqualByComparingTo("2.5");
    }
}
