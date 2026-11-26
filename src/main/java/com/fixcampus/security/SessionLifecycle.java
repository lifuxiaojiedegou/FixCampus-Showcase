package com.fixcampus.security;

import java.time.Clock;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class SessionLifecycle {
    public static final String AUTHENTICATED_AT = "FC_AUTH_AT";
    public static final long ABSOLUTE_TIMEOUT_MILLIS = Duration.ofHours(8).toMillis();
    private final Clock clock;

    public SessionLifecycle(Clock clock) { this.clock = clock; }

    public void authenticationSucceeded(HttpServletRequest request) {
        request.getSession().setAttribute(AUTHENTICATED_AT, clock.millis());
    }

    public boolean expireIfNecessary(HttpServletRequest request) {
        var context = SecurityContextHolder.getContext();
        var authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return false;
        HttpSession session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                try {
                    Object since = session.getAttribute(AUTHENTICATED_AT);
                    // Keep the conservative fallback for sessions predating the marker.
                    long start = since instanceof Long value ? value : session.getCreationTime();
                    if (clock.millis() - start < ABSOLUTE_TIMEOUT_MILLIS) return false;
                    clearAuthentication();
                    session.invalidate();
                    return true;
                } catch (IllegalStateException alreadyInvalidated) {
                    // A concurrent request may already have invalidated this session.
                    clearAuthentication();
                    return true;
                }
            }
        }
        // A loaded authentication cannot survive loss of its backing session.
        clearAuthentication();
        return true;
    }

    private static void clearAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(null);
        SecurityContextHolder.clearContext();
    }
}
