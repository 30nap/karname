package ir.karname.user;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/** Server-side session management ("log out other devices"). */
@Service
public class SessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** Deletes all sessions of the user except {@code keepSessionId} (may be null). */
    public void invalidateAll(String username, String keepSessionId) {
        sessions.findByPrincipalName(username).keySet().stream()
                .filter(id -> !id.equals(keepSessionId))
                .forEach(sessions::deleteById);
    }
}
