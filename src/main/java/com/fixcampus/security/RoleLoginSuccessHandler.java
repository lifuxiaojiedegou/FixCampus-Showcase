package com.fixcampus.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public class RoleLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final SessionLifecycle lifecycle;

    public RoleLoginSuccessHandler(SessionLifecycle lifecycle) { this.lifecycle = lifecycle; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        lifecycle.authenticationSucceeded(request);
        var principal = (CampusPrincipal) authentication.getPrincipal();
        // Ignore saved URLs and client-supplied role/redirect parameters.
        new DefaultRedirectStrategy().sendRedirect(request, response, principal.getRole().getHomePath());
    }
}
