package ir.karname.ledger;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import ir.karname.ledger.CostBasisService.CostBasisView;
import ir.karname.ledger.NetWorthService.HistoryPoint;
import ir.karname.ledger.NetWorthService.NetWorthView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LedgerController {

    private final NetWorthService netWorth;
    private final CostBasisService costBasis;

    public LedgerController(NetWorthService netWorth, CostBasisService costBasis) {
        this.netWorth = netWorth;
        this.costBasis = costBasis;
    }

    @GetMapping("/api/v1/net-worth")
    public NetWorthView netWorth(@AuthenticationPrincipal KarnamePrincipal user) {
        return netWorth.current(user.id());
    }

    @GetMapping("/api/v1/net-worth/history")
    public List<HistoryPoint> history(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(defaultValue = "12") int months) {
        return netWorth.history(user.id(), months);
    }

    @GetMapping("/api/v1/accounts/{id}/cost-basis")
    public CostBasisView costBasis(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return costBasis.forAccount(user.id(), id).orElseThrow(() -> ApiException.notFound("costBasis.notApplicable"));
    }
}
