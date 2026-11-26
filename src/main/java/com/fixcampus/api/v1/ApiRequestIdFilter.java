package com.fixcampus.api.v1;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger LOG = LoggerFactory.getLogger(ApiRequestIdFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/v1";
        String uri = request.getRequestURI();
        return !uri.equals(prefix) && !uri.startsWith(prefix + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = request.getHeaders(HEADER);
        String candidate = values.hasMoreElements() ? values.nextElement() : null;
        String requestId = candidate != null && !values.hasMoreElements() && VALID_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        try (var context = MDC.putCloseable(MDC_KEY, requestId)) {
            try {
                chain.doFilter(request, response);
                String route = Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), "-");
                LOG.info("api_request requestId={} status={} route={}", requestId, response.getStatus(), route);
            } catch (IOException | ServletException | RuntimeException failure) {
                LOG.warn("api_request_failed requestId={} exceptionType={}", requestId, failure.getClass().getSimpleName());
                throw failure;
            }
        }
    }
}
