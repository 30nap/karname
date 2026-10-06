package ir.karname.notification;

import ir.karname.common.security.KarnamePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Any successful write to the API (a transaction, a paid installment, a new cheque…) may start or
 * end a notification condition, so it marks the user's notifications stale.
 */
@Component
class NotificationRefreshInterceptor implements HandlerInterceptor {

    private final NotificationService notifications;

    NotificationRefreshInterceptor(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || response.getStatus() >= 400 || "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || request.getRequestURI().startsWith("/api/v1/notifications")) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof KarnamePrincipal principal) {
            notifications.markStale(principal.id());
        }
    }
}
