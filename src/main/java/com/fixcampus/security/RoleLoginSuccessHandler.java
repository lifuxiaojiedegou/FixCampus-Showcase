package com.fixcampus.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public class RoleLoginSuccessHandler implements AuthenticationSuccessHandler {
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        var principal = (CampusPrincipal) authentication.getPrincipal();
        // Ignore saved URLs and client-supplied role/redirect parameters.
        new DefaultRedirectStrategy().sendRedirect(request, response, principal.getRole().getHomePath());
    }
}
