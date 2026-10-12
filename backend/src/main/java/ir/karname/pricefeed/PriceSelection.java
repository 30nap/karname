package ir.karname.pricefeed;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses, for one commodity, which source's quote becomes the recorded price. Sources are tried
 * in order of preference; a quote counts while it is fresh (twice the source's interval and a few
 * minutes). When most fresh quotes agree, the ones far from them are set aside as likely errors (a
 * Rial figure read as Toman, a price per mesghal read as per gram); when they split, the one
 * nearest the last recorded price wins. A price far from the last recorded one that no other
 * source confirms is held back, so one misconfigured source cannot replace a good price, nor
 * become the reference the next disagreement is settled by.
 */
final class PriceSelection {

    /** How far from the others a quote may be before it is set aside (20%). */
    static final BigDecimal TOLERANCE = new BigDecimal("0.20");
    /** A change from the last recorded price beyond this (50%) needs another source, or the admin, to confirm it. */
    static final BigDecimal JUMP = new BigDecimal("0.50");

    private PriceSelection() {
    }

    /** One source's latest quote for a commodity, with what is needed to judge it. */
    record Quote(long sourceId, String sourceName, int priority, boolean enabled, BigDecimal priceToman, Instant fetchedAt,
            Instant freshUntil) {

        boolean fresh(Instant now) {
            return enabled && now.isBefore(freshUntil);
        }

        /** The same quote from a source switched on, for a run the admin started by hand. */
        Quote switchedOn() {
            return new Quote(sourceId, sourceName, priority, true, priceToman, fetchedAt, freshUntil);
        }
    }

    enum Status {
        /** The quote that is recorded. */
        CHOSEN,
        /** Fresh and in line with the others, but a preferred source has a quote too. */
        BACKUP,
        /** Fresh but too far from the other sources' quotes; not used. */
        OUTLIER,
        /** Would be chosen, but too far from the last recorded price with nothing to confirm it; not recorded. */
        HELD,
        /** Older than the source's interval allows; the source is failing or slow. */
        STALE,
        /** The source is switched off. */
        OFF
    }

    record Verdict(Quote quote, Status status) {
    }

    /** {@code warning} explains a disagreement, a jump or a held price, in Persian. */
    record Decision(Quote chosen, List<Verdict> verdicts, String warning) {
    }

    static Duration freshness(int intervalMinutes) {
        return Duration.ofMinutes(intervalMinutes * 2L + 5);
    }

    static Decision decide(List<Quote> quotes, Instant now, BigDecimal reference) {
        return decide(quotes, now, reference, null);
    }

    /**
     * @param quotes    every source's latest quote for the commodity, in any order
     * @param reference the last recorded automatic price, if a recent one exists
     * @param confirmed the source the admin just fetched by hand, whose jump counts as confirmed; null otherwise
     */
    static Decision decide(List<Quote> quotes, Instant now, BigDecimal reference, Long confirmed) {
        List<Quote> ordered = quotes.stream()
                .sorted(Comparator.comparingInt(Quote::priority).thenComparingLong(Quote::sourceId)).toList();
        List<Quote> fresh = ordered.stream().filter(q -> q.fresh(now)).toList();

        // the largest group of quotes near one another; with a strict majority, the rest are set aside
        List<Quote> outliers = new ArrayList<>();
        boolean majority = false;
        if (fresh.size() >= 3) {
            List<Quote> group = List.of();
            for (Quote anchor : fresh) {
                List<Quote> near = fresh.stream().filter(q -> deviation(q.priceToman(), anchor.priceToman()).compareTo(TOLERANCE) <= 0).toList();
                if (near.size() > group.size()) {
                    group = near;
                }
            }
            if (group.size() * 2 > fresh.size()) {
                majority = true;
                List<Quote> agreed = group;
                fresh.stream().filter(q -> !agreed.contains(q)).forEach(outliers::add);
            }
        }
        List<Quote> usable = fresh.stream().filter(q -> !outliers.contains(q)).toList();

        Quote chosen = usable.isEmpty() ? null : usable.getFirst();
        List<String> warnings = new ArrayList<>();
        if (!outliers.isEmpty()) {
            warnings.add("قیمت " + names(outliers) + " بیش از ۲۰٪ با منابع دیگر فرق داشت و کنار گذاشته شد.");
        } else if (!majority && usable.size() >= 2 && disagree(usable)) {
            if (reference != null) {
                chosen = usable.stream().min(Comparator.comparing(q -> deviation(q.priceToman(), reference))).orElseThrow();
            }
            warnings.add(names(usable) + " بیش از ۲۰٪ با هم اختلاف دارند"
                    + (reference != null ? "؛ قیمتی که به قیمت ثبت‌شده‌ی قبلی نزدیک‌تر است به کار رفت" : "")
                    + ". تنظیمات یکی از آن‌ها (واحد ریال یا تومان، مسیر یا ضریب) احتمالاً اشتباه است.");
        }

        Quote held = null;
        if (chosen != null && reference != null && deviation(chosen.priceToman(), reference).compareTo(JUMP) > 0) {
            Quote candidate = chosen;
            boolean corroborated = majority
                    || usable.stream().anyMatch(q -> q != candidate && deviation(q.priceToman(), candidate.priceToman()).compareTo(TOLERANCE) <= 0);
            boolean byHand = confirmed != null && confirmed == chosen.sourceId();
            if (corroborated || byHand) {
                warnings.add("قیمت «" + chosen.sourceName() + "» بیش از ۵۰٪ با قیمت ثبت‌شده‌ی قبلی فرق دارد؛ اگر درست نیست، تنظیمات منبع را بررسی کنید.");
            } else {
                held = chosen;
                chosen = null;
                warnings.add("قیمت «" + held.sourceName() + "» بیش از ۵۰٪ با قیمت ثبت‌شده‌ی قبلی فرق دارد و منبع دیگری آن را تأیید نمی‌کند، "
                        + "پس ثبت نشد. اگر درست است، «دریافت حالا» را برای همین منبع بزنید؛ اگر نه، تنظیمات آن را بررسی کنید.");
            }
        }

        List<Verdict> verdicts = new ArrayList<>();
        for (Quote q : ordered) {
            Status status = !q.enabled() ? Status.OFF
                    : !q.fresh(now) ? Status.STALE
                    : outliers.contains(q) ? Status.OUTLIER
                    : q == held ? Status.HELD
                    : q == chosen ? Status.CHOSEN
                    : Status.BACKUP;
            verdicts.add(new Verdict(q, status));
        }
        return new Decision(chosen, verdicts, warnings.isEmpty() ? null : String.join(" ", warnings));
    }

    private static boolean disagree(List<Quote> quotes) {
        for (Quote a : quotes) {
            for (Quote b : quotes) {
                if (deviation(a.priceToman(), b.priceToman()).compareTo(TOLERANCE) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String names(List<Quote> quotes) {
        return String.join(" و ", quotes.stream().map(q -> "«" + q.sourceName() + "»").toList());
    }

    /** |a / b − 1|: how far {@code a} is from {@code b}, as a fraction of {@code b}. */
    static BigDecimal deviation(BigDecimal a, BigDecimal b) {
        return a.divide(b, MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
    }
}
