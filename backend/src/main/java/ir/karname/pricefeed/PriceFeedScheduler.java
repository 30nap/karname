package ir.karname.pricefeed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks every minute which sources are due (each has its own interval), and thins old prices nightly. */
@Component
@ConditionalOnProperty(name = "karname.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class PriceFeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceFeedScheduler.class);

    private final PriceFeedService feeds;

    PriceFeedScheduler(PriceFeedService feeds) {
        this.feeds = feeds;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void runDue() {
        feeds.runDue();
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "${karname.timezone:Asia/Tehran}")
    void thin() {
        int removed = feeds.thinOldPrices();
        if (removed > 0) {
            log.info("Thinned {} old fetched prices", removed);
        }
    }
}
