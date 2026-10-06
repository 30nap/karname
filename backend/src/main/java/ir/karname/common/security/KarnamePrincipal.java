package ir.karname.common.security;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * The authenticated user as stored in the HTTP session. Kept minimal (no credentials) because
 * sessions are persisted in the database.
 */
public record KarnamePrincipal(long id, String username, Role role) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return username;
    }

    public List<GrantedAuthority> authorities() {
        return role == Role.ADMIN
                ? List.of(new SimpleGrantedAuthority(Role.ADMIN.authority()), new SimpleGrantedAuthority(Role.USER.authority()))
                : List.of(new SimpleGrantedAuthority(Role.USER.authority()));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
