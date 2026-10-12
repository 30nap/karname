package ir.karname.pricefeed;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses, for one commodity, which source's quote becomes the recorded price. Sources are tried
 * in order of preference; a quote counts while it is fresh (twice the source's interval and a few
 * minutes). With three or more fresh quotes, one far from their median is set aside as a likely
 * error (a Rial figure read as Toman, a price per mesghal read as per gram); with two that
 * disagree, the one nearer the last recorded price wins.
 */
final class PriceSelection {

    /** How far from the others a quote may be before it is set aside (20%). */
    static final BigDecimal TOLERANCE = new BigDecimal("0.20");
    /** A change from the last recorded price beyond this (50%) is flagged, though still recorded. */
    static final BigDecimal JUMP = new BigDecimal("0.50");

    private PriceSelection() {
    }

    /** One source's latest quote for a commodity, with what is needed to judge it. */
    record Quote(long sourceId, String sourceName, int priority, boolean enabled, int intervalMinutes, BigDecimal priceToman,
            Instant fetchedAt) {

        boolean fresh(Instant now) {
            return enabled && !fetchedAt.isBefore(now.minus(freshness(intervalMinutes)));
        }
    }

    enum Status {
        /** The quote that is recorded. */
        CHOSEN,
        /** Fresh and in line with the others, but a preferred source has a quote too. */
        BACKUP,
        /** Fresh but too far from the other sources' quotes; not used. */
        OUTLIER,
        /** Older than the source's interval allows; the source is failing or slow. */
        STALE,
        /** The source is switched off. */
        OFF
    }

    record Verdict(Quote quote, Status status) {
    }

    /** {@code warning} explains a disagreement or a jump worth a look, in Persian. */
    record Decision(Quote chosen, List<Verdict> verdicts, String warning) {
    }

    static Duration freshness(int intervalMinutes) {
        return Duration.ofMinutes(intervalMinutes * 2L + 5);
    }

    /**
     * @param quotes    every source's latest quote for the commodity, in any order
     * @param reference the last recorded automatic price, if a recent one exists
     */
    static Decision decide(List<Quote> quotes, Instant now, BigDecimal reference) {
        List<Quote> ordered = quotes.stream()
                .sorted(Comparator.comparingInt(Quote::priority).thenComparingLong(Quote::sourceId)).toList();
        List<Quote> fresh = ordered.stream().filter(q -> q.fresh(now)).toList();
        List<Quote> outliers = new ArrayList<>();
        if (fresh.size() >= 3) {
            BigDecimal median = median(fresh.stream().map(Quote::priceToman).toList());
            fresh.stream().filter(q -> deviation(q.priceToman(), median).compareTo(TOLERANCE) > 0).forEach(outliers::add);
        }
        List<Quote> usable = fresh.stream().filter(q -> !outliers.contains(q)).toList();

        Quote chosen = usable.isEmpty() ? null : usable.getFirst();
        String warning = null;
        if (usable.size() == 2 && deviation(usable.get(0).priceToman(), usable.get(1).priceToman()).compareTo(TOLERANCE) > 0) {
            if (reference != null) {
                Quote a = usable.get(0);
                Quote b = usable.get(1);
                chosen = deviation(b.priceToman(), reference).compareTo(deviation(a.priceToman(), reference)) < 0 ? b : a;
            }
            warning = "«" + usable.get(0).sourceName() + "» و «" + usable.get(1).sourceName() + "» بیش از ۲۰٪ با هم اختلاف دارند؛ "
                    + "تنظیمات یکی از آن‌ها (واحد ریال یا تومان، مسیر یا ضریب) احتمالاً اشتباه است.";
        } else if (!outliers.isEmpty()) {
            warning = "قیمت " + String.join("، ", outliers.stream().map(q -> "«" + q.sourceName() + "»").toList())
                    + " بیش از ۲۰٪ با منابع دیگر فرق داشت و کنار گذاشته شد.";
        }
        if (warning == null && chosen != null && reference != null && deviation(chosen.priceToman(), reference).compareTo(JUMP) > 0) {
            warning = "قیمت «" + chosen.sourceName() + "» بیش از ۵۰٪ با قیمت ثبت‌شده‌ی قبلی فرق دارد؛ اگر درست نیست، تنظیمات منبع را بررسی کنید.";
        }

        List<Verdict> verdicts = new ArrayList<>();
        for (Quote q : ordered) {
            Status status = !q.enabled() ? Status.OFF
                    : !q.fresh(now) ? Status.STALE
                    : outliers.contains(q) ? Status.OUTLIER
                    : q == chosen ? Status.CHOSEN
                    : Status.BACKUP;
            verdicts.add(new Verdict(q, status));
        }
        return new Decision(chosen, verdicts, warning);
    }

    /** |a / b − 1|: how far {@code a} is from {@code b}, as a fraction of {@code b}. */
    static BigDecimal deviation(BigDecimal a, BigDecimal b) {
        return a.divide(b, MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, 8, RoundingMode.HALF_EVEN);
    }
}
