package ir.karname.notification;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.notification.NotificationService.NotificationView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationView> list(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam(defaultValue = "false") boolean unread) {
        notifications.refreshThrottled(user.id());
        return notifications.list(user.id(), unread);
    }

    /** Polled by the app; also re-evaluates the rules (throttled). */
    @GetMapping("/count")
    public Map<String, Long> count(@AuthenticationPrincipal KarnamePrincipal user) {
        notifications.refreshThrottled(user.id());
        return Map.of("unread", notifications.unreadCount(user.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        notifications.markRead(user.id(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal KarnamePrincipal user) {
        notifications.markAllRead(user.id());
    }
}
