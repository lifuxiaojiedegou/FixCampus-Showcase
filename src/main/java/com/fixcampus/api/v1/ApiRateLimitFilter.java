package com.fixcampus.api.v1;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed in the API security chain after Origin, CSRF and authorization checks. */
final class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher.Builder PATH = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher LOGIN = PATH.matcher(HttpMethod.POST, "/api/v1/auth/login");
    static final RequestMatcher ATTACHMENT_WRITE = new OrRequestMatcher(
            PATH.matcher(HttpMethod.POST, "/api/v1/orders/{id}/attachments"),
            PATH.matcher(HttpMethod.DELETE, "/api/v1/orders/{id}/attachments/{aid}"),
            PATH.matcher(HttpMethod.POST, "/api/v1/worker/orders/{id}/attachments"));
    private static final RequestMatcher ORDER_WRITE = new OrRequestMatcher(
            ATTACHMENT_WRITE,
            PATH.matcher(HttpMethod.POST, "/api/v1/notifications/{id}/read"),
            PATH.matcher(HttpMethod.POST, "/api/v1/notifications/read-all"),
            PATH.matcher(HttpMethod.POST, "/api/v1/orders"),
            PATH.matcher(HttpMethod.POST, "/api/v1/orders/{id}/{action:assign|start|complete|confirm|cancel}"));

    private final ObjectMapper mapper;
    private final Bucket logins;
    private final Bucket writes;

    ApiRateLimitFilter(ObjectMapper mapper, Settings settings) {
        this.mapper = mapper;
        logins = new Bucket(settings.loginRequests(), settings.loginWindowSeconds(), settings.maxKeysPerBucket());
        writes = new Bucket(settings.writeRequests(), settings.writeWindowSeconds(), settings.maxKeysPerBucket());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfter = 0;
        if (LOGIN.matches(request)) {
            retryAfter = logins.acquire(request.getRemoteAddr());
        } else if (ORDER_WRITE.matches(request)) {
            var session = request.getSession(false);
            String key = session != null ? "session:" + session.getId() : "address:" + request.getRemoteAddr();
            retryAfter = writes.acquire(key);
        }
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            ApiProblem.write(response, mapper, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "RATE_LIMITED", "请求过于频繁，请稍后再试");
            return;
        }
        chain.doFilter(request, response);
    }

    @ConfigurationProperties("fixcampus.rate-limit")
    record Settings(boolean enabled, @DefaultValue("30") int loginRequests, @DefaultValue("60") int loginWindowSeconds,
            @DefaultValue("120") int writeRequests, @DefaultValue("60") int writeWindowSeconds,
            @DefaultValue("10000") int maxKeysPerBucket) { }

    private static final class Bucket {
        private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();
        private final int limit;
        private final long durationNanos;
        private final int maxKeys;

        private Bucket(int limit, int windowSeconds, int maxKeys) {
            if (limit <= 0 || windowSeconds <= 0 || maxKeys <= 0) {
                throw new IllegalArgumentException("Rate-limit counts, window seconds and key capacity must be positive.");
            }
            this.limit = limit;
            this.durationNanos = TimeUnit.SECONDS.toNanos(windowSeconds);
            this.maxKeys = maxKeys;
        }

        /** Return zero on admission or the rounded-up wait before another attempt. */
        private synchronized long acquire(String key) {
            long now = System.nanoTime();
            // Insertion order is also expiration order because every window has the same duration.
            var oldest = windows.firstEntry();
            while (oldest != null && now - oldest.getValue().started >= durationNanos) {
                windows.pollFirstEntry();
                oldest = windows.firstEntry();
            }
            var window = windows.get(key);
            if (window == null) {
                // Preserve active quotas when the number of distinct clients reaches capacity.
                if (windows.size() == maxKeys) return retryAfter(now, oldest.getValue());
                window = new Window(now);
                windows.put(key, window);
            }
            if (window.used == limit) return retryAfter(now, window);
            window.used++;
            return 0;
        }

        private long retryAfter(long now, Window window) {
            return 1 + TimeUnit.NANOSECONDS.toSeconds(durationNanos - (now - window.started) - 1);
        }

        private static final class Window {
            private final long started;
            private int used;

            private Window(long started) {
                this.started = started;
            }
        }
    }
}
