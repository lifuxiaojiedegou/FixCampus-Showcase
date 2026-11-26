package com.fixcampus.api.v1;

import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.security.CampusUserDetailsService;
import com.fixcampus.security.SessionLifecycle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@Profile({"web-slice", "demo", "prod"})
@EnableConfigurationProperties(ApiRateLimitFilter.Settings.class)
public class ApiSecurityConfig {
    @Bean
    AuthenticationManager apiAuthenticationManager(CampusUserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }
    @Bean HttpSessionCsrfTokenRepository apiCsrfTokens() { return new HttpSessionCsrfTokenRepository(); }
    @Bean HttpSessionSecurityContextRepository apiContexts() { return new HttpSessionSecurityContextRepository(); }

    @Bean @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, ObjectMapper mapper, HttpSessionCsrfTokenRepository tokens,
            HttpSessionSecurityContextRepository contexts, SessionLifecycle lifecycle, ApiRateLimitFilter.Settings rateLimit,
            @Value("${fixcampus.web.origin:http://127.0.0.1:5173}") String origin)
            throws Exception {
        URI parsed = URI.create(origin);
        if (parsed.getHost() == null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                || parsed.getUserInfo() != null || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty())
                || !("https".equals(parsed.getScheme()) || ("http".equals(parsed.getScheme())
                    && "127.0.0.1".equals(parsed.getHost())))) throw new IllegalArgumentException("Invalid Web origin");
        http.securityMatcher("/api/v1/**")
                .securityContext(c -> c.securityContextRepository(contexts))
                .csrf(c -> c.csrfTokenRepository(tokens).csrfTokenRequestHandler(new ApiCsrfTokens()))
                .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/me", "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/notifications", "/api/v1/notifications/unread-count",
                                "/api/v1/notifications/read-all", "/api/v1/notifications/*/read").authenticated()
                        .requestMatchers("/api/v1/admin/workers", "/api/v1/admin/orders", "/api/v1/admin/orders/*",
                                "/api/v1/admin/statistics", "/api/v1/orders/*/assign",
                                "/api/v1/admin/orders/*/attachments",
                                "/api/v1/admin/orders/*/attachments/*/content").hasRole("ADMIN")
                        .requestMatchers("/api/v1/worker/orders", "/api/v1/worker/orders/*",
                                "/api/v1/orders/*/start", "/api/v1/orders/*/complete",
                                "/api/v1/worker/orders/*/attachments",
                                "/api/v1/worker/orders/*/attachments/*/content").hasRole("WORKER")
                        .requestMatchers("/api/v1/orders/*/confirm", "/api/v1/orders/*/cancel").hasRole("STUDENT")
                        .requestMatchers("/api/v1/orders", "/api/v1/orders/*", "/api/v1/orders/*/attachments",
                                "/api/v1/orders/*/attachments/*", "/api/v1/orders/*/attachments/*/content").hasRole("STUDENT")
                        .anyRequest().denyAll())
                .requestCache(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .logout(c -> c.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                        ApiProblem.write(res, mapper, 401, "AUTH_REQUIRED", "请先登录"))
                    .accessDeniedHandler((req, res, ex) -> ApiProblem.write(res, mapper, 403,
                        ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_REJECTED" : "ROLE_FORBIDDEN",
                        "请求被拒绝")))
                .addFilterBefore(new ApiGate(mapper, origin, lifecycle), CsrfFilter.class);
        if (rateLimit.enabled()) {
            http.addFilterAfter(new ApiRateLimitFilter(mapper, rateLimit), AuthorizationFilter.class);
        }
        return http.build();
    }

    private static final class ApiCsrfTokens implements CsrfTokenRequestHandler {
        private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
            delegate.handle(request, response, token);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
            // Accessing form parameters here would parse multipart before MVC can handle upload errors.
            if (ApiRateLimitFilter.ATTACHMENT_WRITE.matches(request)
                    && request.getHeader(token.getHeaderName()) == null) return null;
            return delegate.resolveCsrfTokenValue(request, token);
        }
    }

    private static final class ApiGate extends OncePerRequestFilter {
        private final ObjectMapper mapper;
        private final String origin;
        private final SessionLifecycle lifecycle;
        ApiGate(ObjectMapper mapper, String origin, SessionLifecycle lifecycle) {
            this.mapper = mapper; this.origin = origin; this.lifecycle = lifecycle;
        }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            res.setHeader("Cache-Control", "no-store");
            lifecycle.expireIfNecessary(req);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
            String path = req.getRequestURI().substring(req.getContextPath().length());
            boolean publicPath = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/csrf");
            if (!publicPath && !authenticated) {
                ApiProblem.write(res, mapper, 401, "AUTH_REQUIRED", "请先登录"); return;
            }
            if (!java.util.Set.of("GET", "HEAD").contains(req.getMethod()) && !origin.equals(req.getHeader("Origin"))) {
                ApiProblem.write(res, mapper, 403, "ORIGIN_REJECTED", "请求来源不被允许"); return;
            }
            chain.doFilter(req, res);
        }
    }
}
