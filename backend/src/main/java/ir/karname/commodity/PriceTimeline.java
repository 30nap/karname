package ir.karname.commodity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All known prices of a set of commodities, for valuing balances at arbitrary past moments
 * (month-end net worth, historical reports) without a query per lookup.
 */
public final class PriceTimeline {

    private final Map<Long, List<PricePoint>> byCommodity = new HashMap<>();
    private final Set<Long> tomanIds;

    public PriceTimeline(List<PricePoint> points, Set<Long> tomanIds) {
        this.tomanIds = tomanIds;
        for (PricePoint p : points) {
            byCommodity.computeIfAbsent(p.commodityId(), k -> new ArrayList<>()).add(p);
        }
        byCommodity.values().forEach(list -> list.sort(Comparator.comparing(PricePoint::pricedAt)));
    }

    /** Latest price at or before {@code at}; Toman is always 1. */
    public Optional<PricePoint> priceAt(long commodityId, Instant at) {
        if (tomanIds.contains(commodityId)) {
            return Optional.of(PricePoint.toman(commodityId));
        }
        List<PricePoint> list = byCommodity.get(commodityId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        int lo = 0;
        int hi = list.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (!list.get(mid).pricedAt().isAfter(at)) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? Optional.empty() : Optional.of(list.get(found));
    }
}
