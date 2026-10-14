package ir.karname.pricefeed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "karname.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class StockScheduler {

    private final StockService stocks;

    StockScheduler(StockService stocks) {
        this.stocks = stocks;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    void runDue() {
        stocks.runDue();
    }
}
