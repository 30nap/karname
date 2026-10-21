package ir.karname.ai.propose;

import ir.karname.ai.llm.JsonSchema;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.commodity.PriceService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** A price the user knows (a car's value, today's coin price at their exchange), recorded for them alone. */
@Component
class PriceProposals implements ProposalKind<PriceProposals.ManualPrice> {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");

    /** {@code pricedAt} null: the moment it is applied. {@code previousToman}: the price shown as before, for the card. */
    public record ManualPrice(String commodity, String nameFa, String unitFa, BigDecimal priceToman, LocalDate date, Instant pricedAt,
            BigDecimal previousToman) {
    }

    private final CommodityService commodities;
    private final PriceService prices;

    PriceProposals(CommodityService commodities, PriceService prices) {
        this.commodities = commodities;
        this.prices = prices;
    }

    @Override
    public String kind() {
        return "PRICE";
    }

    @Override
    public String tool() {
        return "propose_price";
    }

    @Override
    public String label() {
        return "پیشنهاد قیمت";
    }

    @Override
    public String description() {
        return "Proposes recording a price the user states for one unit of an asset (a gram of gold, a coin, a dollar, their car or "
                + "flat, a share), for the user to confirm. It counts for this user only and values their accounts until a newer price "
                + "arrives. Check get_prices first.";
    }

    @Override
    public JsonSchema input() {
        JsonSchema.ObjectBuilder b = JsonSchema.object(null)
                .required("unit", JsonSchema.string("The asset, as a code or name (USD, GOLD18, «سکه امامی», a custom asset's name)")
                        .maxLength(60));
        Inputs.money(b, "price", "The price of one unit", true);
        return b.optional("date", Inputs.date("The day the price is for; default now"))
                .optional("replaces_previous", Inputs.replacesPrevious())
                .build();
    }

    @Override
    public Class<ManualPrice> payloadType() {
        return ManualPrice.class;
    }

    @Override
    public List<Prepared<ManualPrice>> prepare(Request request) {
        JsonNode in = request.input();
        CommodityView unit = request.lookup().unit("unit", Inputs.text(in, "unit"));
        if ("IRT".equals(unit.code())) {
            throw new IllegalArgumentException("unit: the Toman has no price");
        }
        List<String> warnings = new ArrayList<>();
        BigDecimal price = Inputs.toman(in, "price");
        LocalDate date = Inputs.date(in, "date");
        Instant at = null;
        if (date != null && date.isAfter(request.today())) {
            throw new IllegalArgumentException("date: a price cannot be for a future day");
        }
        if (date != null && date.isBefore(request.today())) {
            // noon in Tehran, as the price form records past days
            at = date.atTime(LocalTime.NOON).atZone(TEHRAN).toInstant();
        }
        BigDecimal previous = unit.latestPrice() == null ? null : unit.latestPrice().priceToman();
        if (previous != null && previous.signum() > 0) {
            BigDecimal change = price.divide(previous, MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
            if (change.compareTo(new BigDecimal("0.5")) > 0) {
                warnings.add("این قیمت بیش از ۵۰٪ با آخرین قیمت ثبت‌شده فرق دارد؛ تومان یا ریال بودن آن را بررسی کنید.");
            }
        }
        return List.of(Prepared.of(new ManualPrice(unit.code(), unit.nameFa(), unit.unitFa(), price, date, at, previous), warnings));
    }

    @Override
    public Object execute(long userId, ManualPrice p) {
        return prices.recordManual(userId, commodities.require(userId, p.commodity()), p.priceToman(), p.pricedAt(), false);
    }

    @Override
    public Card card(long userId, ManualPrice p, Object result) {
        Card.Builder card = Card.builder("قیمت «" + p.nameFa() + "»")
                .money("قیمت هر " + p.unitFa(), p.priceToman(), true)
                .date("برای روز", p.date())
                .money("آخرین قیمت ثبت‌شده", p.previousToman());
        if (p.previousToman() != null && p.previousToman().signum() > 0) {
            card.percent("تغییر", p.priceToman().divide(p.previousToman(), MathContext.DECIMAL64).subtract(BigDecimal.ONE)
                    .movePointRight(2).setScale(1, RoundingMode.HALF_EVEN));
        }
        card.effect("این قیمت فقط برای شما ثبت می‌شود و ارزش دارایی‌هایتان با آن حساب می‌شود تا قیمت تازه‌تری برسد.");
        return card.build();
    }

    @Override
    public String link(ManualPrice payload, Object result) {
        return "/assets";
    }
}
