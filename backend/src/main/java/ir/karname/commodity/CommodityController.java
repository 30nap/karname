package ir.karname.commodity;

import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.commodity.CommodityService.CustomCommodityRequest;
import ir.karname.commodity.PriceService.PriceRecordView;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CommodityController {

    private final CommodityService commodities;
    private final PriceService prices;
    private final Clock clock;

    public CommodityController(CommodityService commodities, PriceService prices, Clock clock) {
        this.commodities = commodities;
        this.prices = prices;
        this.clock = clock;
    }

    public record PriceRequest(@NotBlank String commodity, @NotNull BigDecimal priceToman, Instant pricedAt, boolean global) {
    }

    @GetMapping("/commodities")
    public List<CommodityView> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return commodities.list(user.id());
    }

    @PostMapping("/commodities")
    @ResponseStatus(HttpStatus.CREATED)
    public CommodityView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody CustomCommodityRequest request) {
        return commodities.view(commodities.createCustom(user.id(), request), null);
    }

    @PutMapping("/commodities/{code}")
    public CommodityView update(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String code,
            @RequestBody CustomCommodityRequest request) {
        Commodity commodity = commodities.updateCustom(user.id(), code, request);
        Map<Long, PricePoint> latest = prices.latestPrices(user.id(), List.of(commodity.getId()), clock.instant());
        return commodities.view(commodity, latest.get(commodity.getId()));
    }

    @DeleteMapping("/commodities/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String code) {
        commodities.deleteCustom(user.id(), code);
    }

    @GetMapping("/prices")
    public List<PriceRecordView> history(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam String commodity,
            @RequestParam(defaultValue = "200") int limit) {
        return prices.history(user.id(), commodities.require(user.id(), commodity), limit);
    }

    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceRecordView record(@AuthenticationPrincipal KarnamePrincipal user, @Valid @RequestBody PriceRequest request) {
        if (request.global() && !user.isAdmin()) {
            throw ApiException.forbidden("price.globalAdminOnly");
        }
        Commodity commodity = commodities.require(user.id(), request.commodity());
        if (request.global() && commodity.isCustom()) {
            throw ApiException.badRequest("price.globalCustom");
        }
        Price price = prices.recordManual(user.id(), commodity, request.priceToman(), request.pricedAt(), request.global());
        return new PriceRecordView(price.getId(), commodity.getCode(), price.getPriceToman(), price.getPricedAt(), price.getSource(),
                price.getUserId() != null);
    }

    @DeleteMapping("/prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrice(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        prices.delete(user.id(), id, user.isAdmin());
    }
}
