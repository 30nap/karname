package ir.karname.pricefeed;

import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.pricefeed.TsetmcClient.StockInstrument;
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

import java.util.List;

/** Ordinary users manage only the automatic stock prices of their own commodities. */
@RestController
@RequestMapping("/api/v1")
public class StockController {

    private final StockService stocks;

    public StockController(StockService stocks) {
        this.stocks = stocks;
    }

    public record InstrumentRequest(String instrumentId) {
    }

    @GetMapping("/securities/search")
    public List<StockInstrument> search(@RequestParam(defaultValue = "") String q) {
        return stocks.search(q);
    }

    @PostMapping("/securities")
    @ResponseStatus(HttpStatus.CREATED)
    public CommodityView create(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody InstrumentRequest request) {
        return stocks.create(user.id(), request == null ? null : request.instrumentId());
    }

    @PutMapping("/commodities/{code}/security")
    public CommodityView link(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String code,
            @RequestBody InstrumentRequest request) {
        return stocks.link(user.id(), code, request == null ? null : request.instrumentId());
    }

    @DeleteMapping("/commodities/{code}/security")
    public CommodityView unlink(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String code) {
        return stocks.unlink(user.id(), code);
    }

    @PostMapping("/commodities/{code}/security/refresh")
    public CommodityView refresh(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String code) {
        return stocks.refresh(user.id(), code);
    }
}
