package org.gms.soloacceptance;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The same native administrator identity guards observations and actor ownership. */
public final class AcceptanceAccess {
    private AcceptanceAccess() { }

    public static String requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ObservationException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
        if (authentication.getAuthorities().stream().noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new ObservationException(HttpStatus.FORBIDDEN, "administrator_required");
        }
        return authentication.getName();
    }
}
