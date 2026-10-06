package ir.karname.commodity;

import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CommodityService {

    private final CommodityRepository commodities;
    private final PriceService prices;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CommodityService(CommodityRepository commodities, PriceService prices, JdbcClient jdbc, Clock clock) {
        this.commodities = commodities;
        this.prices = prices;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record LatestPriceView(BigDecimal priceToman, Instant pricedAt, String source, boolean personal, boolean stale) {
    }

    public record CommodityView(String code, String nameFa, String unitFa, CommodityKind kind, int scale, boolean custom,
            LatestPriceView latestPrice) {
    }

    public record CustomCommodityRequest(String nameFa, String unitFa, CommodityKind kind, Integer scale) {
    }

    @Transactional(readOnly = true)
    public List<CommodityView> list(long userId) {
        List<Commodity> visible = commodities.findVisible(userId).stream().filter(Commodity::isActive).toList();
        Map<Long, PricePoint> latest = prices.latestPrices(userId, visible.stream().map(Commodity::getId).toList(), clock.instant());
        return visible.stream().map(c -> view(c, latest.get(c.getId()))).toList();
    }

    public CommodityView view(Commodity c, PricePoint price) {
        LatestPriceView priceView = null;
        if (price != null && !c.isToman()) {
            priceView = new LatestPriceView(price.priceToman(), price.pricedAt(), price.source(), price.personal(), isStale(c, price.pricedAt()));
        }
        return new CommodityView(c.getCode(), c.getNameFa(), c.getUnitFa(), c.getKind(), c.getScale(), c.isCustom(), priceView);
    }

    public boolean isStale(Commodity c, Instant pricedAt) {
        Duration staleAfter = c.getKind().staleAfter();
        return staleAfter != null && pricedAt.isBefore(clock.instant().minus(staleAfter));
    }

    /** A commodity the user may use (built-in or their own); 404 otherwise. */
    @Transactional(readOnly = true)
    public Commodity require(long userId, String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("commodity.required");
        }
        return commodities.findVisibleByCode(userId, code.trim().toUpperCase())
                .filter(Commodity::isActive)
                .orElseThrow(() -> ApiException.notFound("commodity.notFound"));
    }

    @Transactional(readOnly = true)
    public Commodity toman() {
        return commodities.findBuiltIn(Commodity.TOMAN).orElseThrow();
    }

    @Transactional
    public Commodity createCustom(long userId, CustomCommodityRequest request) {
        String name = PersianText.clean(request.nameFa());
        String unit = PersianText.clean(request.unitFa());
        if (name == null || name.length() > 100 || unit == null || unit.length() > 30) {
            throw ApiException.badRequest("commodity.invalid");
        }
        CommodityKind kind = request.kind() == null ? CommodityKind.OTHER : request.kind();
        if (kind == CommodityKind.TOMAN) {
            throw ApiException.badRequest("commodity.invalid");
        }
        int scale = request.scale() == null ? 0 : request.scale();
        if (scale < 0 || scale > 8) {
            throw ApiException.badRequest("commodity.invalid");
        }
        byte[] random = new byte[4];
        ThreadLocalRandom.current().nextBytes(random);
        String code = "C_" + HexFormat.of().withUpperCase().formatHex(random);
        return commodities.save(new Commodity(code, userId, name, unit, kind, scale));
    }

    @Transactional
    public Commodity updateCustom(long userId, String code, CustomCommodityRequest request) {
        Commodity commodity = requireOwnCustom(userId, code);
        String name = PersianText.clean(request.nameFa());
        String unit = PersianText.clean(request.unitFa());
        if (name == null || name.length() > 100 || unit == null || unit.length() > 30) {
            throw ApiException.badRequest("commodity.invalid");
        }
        commodity.setNameFa(name);
        commodity.setUnitFa(unit);
        if (request.kind() != null && request.kind() != CommodityKind.TOMAN) {
            commodity.setKind(request.kind());
        }
        return commodity;
    }

    @Transactional
    public void deleteCustom(long userId, String code) {
        Commodity commodity = requireOwnCustom(userId, code);
        long used = jdbc.sql("SELECT count(*) FROM accounts WHERE commodity_id = ?").param(commodity.getId()).query(Long.class).single();
        if (used > 0) {
            throw ApiException.conflict("commodity.inUse");
        }
        commodities.delete(commodity);
    }

    private Commodity requireOwnCustom(long userId, String code) {
        Commodity commodity = require(userId, code);
        if (!commodity.isCustom()) {
            throw ApiException.forbidden("commodity.builtIn");
        }
        return commodity;
    }
}
