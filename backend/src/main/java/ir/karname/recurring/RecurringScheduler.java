package ir.karname.recurring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Posts due AUTO occurrences shortly after midnight Tehran time, and catches up at startup. */
@Component
@ConditionalOnProperty(name = "karname.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class RecurringScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringScheduler.class);

    private final RecurringService recurring;

    RecurringScheduler(RecurringService recurring) {
        this.recurring = recurring;
    }

    @EventListener(ApplicationReadyEvent.class)
    void catchUp() {
        run();
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "${karname.timezone:Asia/Tehran}")
    void run() {
        int total = 0;
        for (Long userId : recurring.usersWithAutoRules()) {
            try {
                total += recurring.runAuto(userId);
            } catch (RuntimeException e) {
                log.error("Recurring postings failed for user {}", userId, e);
            }
        }
        if (total > 0) {
            log.info("Posted {} recurring transactions", total);
        }
    }
}
