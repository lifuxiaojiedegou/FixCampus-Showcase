package com.fixcampus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fixcampus.security.SessionLifecycle;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionLifecycleTest {
    private static final long START = 1_800_000_000_000L;
    private static final long LIMIT = SessionLifecycle.ABSOLUTE_TIMEOUT_MILLIS;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void authenticated() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("fixture", null, java.util.List.of()));
        SecurityContextHolder.setContext(context);
    }
    private SessionLifecycle at(long millis) {
        return new SessionLifecycle(Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void absoluteBoundaryClearsContextAndInvalidatesSession(long offset) {
        authenticated();
        var request = new MockHttpServletRequest(); var session = new MockHttpSession();
        session.setAttribute(SessionLifecycle.AUTHENTICATED_AT, START); request.setSession(session);
        var original = SecurityContextHolder.getContext();
        assertThat(at(START + LIMIT + offset).expireIfNecessary(request)).isEqualTo(offset >= 0);
        assertThat(session.isInvalid()).isEqualTo(offset >= 0);
        assertThat(original.getAuthentication() == null).isEqualTo(offset >= 0);
        assertThat(SecurityContextHolder.getContext().getAuthentication() == null).isEqualTo(offset >= 0);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void missingMarkerUsesCreationTimeWithoutWritingANewTimestamp(long offset) {
        authenticated();
        var session = mock(HttpSession.class);
        when(session.getCreationTime()).thenReturn(START);
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(session);
        assertThat(at(START + LIMIT + offset).expireIfNecessary(request)).isEqualTo(offset >= 0);
        verify(session, never()).setAttribute(anyString(), any());
        verify(session, times(offset >= 0 ? 1 : 0)).invalidate();
    }

    @Test void anonymousOldSessionDoesNotPreventNewAuthentication() {
        var request = new MockHttpServletRequest(); var session = new MockHttpSession(); request.setSession(session);
        var lifecycle = at(session.getCreationTime() + LIMIT * 2);
        assertThat(lifecycle.expireIfNecessary(request)).isFalse();
        assertThat(session.getAttribute(SessionLifecycle.AUTHENTICATED_AT)).isNull();
        lifecycle.authenticationSucceeded(request);
        authenticated();
        assertThat(lifecycle.expireIfNecessary(request)).isFalse();
        assertThat(session.getAttribute(SessionLifecycle.AUTHENTICATED_AT)).isEqualTo(session.getCreationTime() + LIMIT * 2);
    }

    @Test void concurrentExpirationClearsEachRequestWithoutRevivingTheSession() throws Exception {
        var session = new MockHttpSession(); session.setAttribute(SessionLifecycle.AUTHENTICATED_AT, START);
        var lifecycle = at(START + LIMIT); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                var request = new MockHttpServletRequest(); request.setSession(session); authenticated(); start.await();
                try {
                    assertThat(lifecycle.expireIfNecessary(request)).isTrue();
                    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                } finally { SecurityContextHolder.clearContext(); }
                return true;
            })).toList();
            start.countDown();
            for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void anonymousRequestDoesNotCreateASession() {
        var request = new MockHttpServletRequest();
        assertThat(at(START).expireIfNecessary(request)).isFalse();
        assertThat(request.getSession(false)).isNull();
    }
}
