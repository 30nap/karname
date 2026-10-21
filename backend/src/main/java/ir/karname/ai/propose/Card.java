package ir.karname.ai.propose;

import ir.karname.ai.guard.Privacy;
import ir.karname.common.jalali.JalaliDate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a proposal card shows: every value that will be saved, the figures the server worked out
 * (an installment, a goal's monthly need), what else happens when it is applied, and warnings.
 * The app formats values by their {@link Format}, in the user's digits and currency unit.
 */
public record Card(String title, List<Line> lines, List<String> effects, List<String> warnings) {

    public enum Format {
        TEXT,
        /** An amount in Toman. */
        MONEY,
        /** An amount of {@link Line#unit()}, a commodity code. */
        QUANTITY,
        /** An ISO date, shown in the Jalali calendar. */
        DATE,
        NUMBER,
        /** A percentage, e.g. 23 for 23%. */
        PERCENT
    }

    /** {@code main}: the figure the card leads with, which the app also spells out in words. */
    public record Line(String label, String value, Format format, String unit, boolean main) {
    }

    public Card {
        lines = List.copyOf(lines);
        effects = List.copyOf(effects);
        warnings = List.copyOf(warnings);
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** The same card with more warnings first (what was assumed when the request was prepared). */
    public Card withWarnings(List<String> earlier) {
        if (earlier.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(earlier);
        warnings.stream().filter(w -> !all.contains(w)).forEach(all::add);
        return new Card(title, lines, effects, all);
    }

    /** The card as told to the model: labels with plain values, identifiers masked. */
    public Map<String, Object> forModel() {
        Map<String, String> details = new LinkedHashMap<>();
        for (Line line : lines) {
            details.put(line.label(), switch (line.format()) {
                case MONEY -> group(new BigDecimal(line.value())) + " تومان";
                case QUANTITY -> group(new BigDecimal(line.value())) + " " + line.unit();
                case DATE -> JalaliDate.from(LocalDate.parse(line.value())).toString();
                case PERCENT -> line.value() + "%";
                case NUMBER -> line.value();
                case TEXT -> Privacy.mask(line.value());
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", Privacy.mask(title));
        out.put("details", details);
        if (!effects.isEmpty()) {
            out.put("effects", effects.stream().map(Privacy::mask).toList());
        }
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings.stream().map(Privacy::mask).toList());
        }
        return out;
    }

    static String group(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.########", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format.format(value);
    }

    static String plain(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0) : v).toPlainString();
    }

    /** Collects lines in order; null values are left out. */
    public static final class Builder {

        private final String title;
        private final List<Line> lines = new ArrayList<>();
        private final List<String> effects = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private Builder(String title) {
            this.title = title;
        }

        public Builder text(String label, String value) {
            if (value != null && !value.isBlank()) {
                lines.add(new Line(label, value, Format.TEXT, null, false));
            }
            return this;
        }

        public Builder money(String label, BigDecimal toman) {
            return money(label, toman, false);
        }

        public Builder money(String label, BigDecimal toman, boolean main) {
            if (toman != null) {
                lines.add(new Line(label, plain(toman.setScale(Math.min(Math.max(toman.scale(), 0), 2), RoundingMode.HALF_EVEN)),
                        Format.MONEY, null, main));
            }
            return this;
        }

        public Builder quantity(String label, BigDecimal amount, String commodity) {
            return quantity(label, amount, commodity, false);
        }

        public Builder quantity(String label, BigDecimal amount, String commodity, boolean main) {
            if (amount != null) {
                lines.add(new Line(label, plain(amount), Format.QUANTITY, commodity, main));
            }
            return this;
        }

        public Builder date(String label, LocalDate date) {
            if (date != null) {
                lines.add(new Line(label, date.toString(), Format.DATE, null, false));
            }
            return this;
        }

        public Builder number(String label, Number value) {
            if (value != null) {
                lines.add(new Line(label, value instanceof BigDecimal b ? plain(b) : value.toString(), Format.NUMBER, null, false));
            }
            return this;
        }

        public Builder percent(String label, BigDecimal value) {
            if (value != null) {
                lines.add(new Line(label, plain(value), Format.PERCENT, null, false));
            }
            return this;
        }

        public Builder effect(String text) {
            effects.add(text);
            return this;
        }

        public Builder warning(String text) {
            if (!warnings.contains(text)) {
                warnings.add(text);
            }
            return this;
        }

        public Card build() {
            return new Card(title, lines, effects, warnings);
        }
    }
}
