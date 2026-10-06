package ir.karname.notification;

import ir.karname.user.User;
import ir.karname.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-evaluates notification rules for every active user hourly, and purges old ones nightly. */
@Component
@ConditionalOnProperty(name = "karname.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService notifications;
    private final UserRepository users;

    NotificationScheduler(NotificationService notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Scheduled(cron = "0 15 * * * *", zone = "${karname.timezone:Asia/Tehran}")
    void refreshAll() {
        for (User user : users.findAllByOrderByIdAsc()) {
            if (!user.isEnabled()) {
                continue;
            }
            try {
                notifications.refresh(user.getId());
            } catch (RuntimeException e) {
                log.error("Notification rules failed for user {}", user.getId(), e);
            }
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${karname.timezone:Asia/Tehran}")
    void purge() {
        int removed = notifications.purgeOld();
        if (removed > 0) {
            log.info("Removed {} old notifications", removed);
        }
    }
}
