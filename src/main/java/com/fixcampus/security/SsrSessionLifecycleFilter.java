package com.fixcampus.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Constructed only inside the SecurityFilterChain, never a servlet filter bean. */
public class SsrSessionLifecycleFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher REGISTRATION = PathPatternRequestMatcher.withDefaults().matcher("/register");
    private final SessionLifecycle lifecycle;
    private final AuthenticationEntryPoint login;
    private final boolean registrationEnabled;

    public SsrSessionLifecycleFilter(SessionLifecycle lifecycle, AuthenticationEntryPoint login, boolean registrationEnabled) {
        this.lifecycle = lifecycle;
        this.login = login;
        this.registrationEnabled = registrationEnabled;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        boolean expired = lifecycle.expireIfNecessary(request);
        if (!registrationEnabled && REGISTRATION.matches(request)) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        if (expired && !publicEntrance(request)) {
            login.commence(request, response, new InsufficientAuthenticationException("Session expired"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean publicEntrance(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean publicAuth = path.equals("/login") || (registrationEnabled && REGISTRATION.matches(request));
        if ("POST".equals(request.getMethod())) return publicAuth;
        return "GET".equals(request.getMethod()) && (publicAuth || path.equals("/")
                || path.equals("/error") || path.startsWith("/css/") || path.startsWith("/js/"));
    }
}
