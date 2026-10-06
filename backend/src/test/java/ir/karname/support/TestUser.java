package ir.karname.support;

import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.security.Role;

public record TestUser(long id, String username, Role role) {

    public KarnamePrincipal principal() {
        return new KarnamePrincipal(id, username, role);
    }
}
