package com.fixcampus.api.v1;

import java.time.LocalDateTime;
import java.util.List;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.NotificationNotFoundException;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.NotificationService;
import com.fixcampus.service.NotificationService.NotificationItem;
import com.fixcampus.service.NotificationService.NotificationPage;
import com.fixcampus.service.NotificationService.ReadAllResult;
import com.fixcampus.service.NotificationService.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationsApi.class, properties = {
        "fixcampus.web.origin=http://127.0.0.1:5173", "fixcampus.rate-limit.enabled=true",
        "fixcampus.rate-limit.write-requests=1"})
@ActiveProfiles("web-slice")
@Import(ApiSecurityConfig.class)
class NotificationsApiTest {
    private static final String BASE = "/api/v1/notifications";
    private static final String READ = "/101/read";
    private static final String READ_ALL = "/read-all";
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-11-26T15:00:01");
    private static final LocalDateTime READ_AT = LocalDateTime.parse("2026-11-26T15:05:01");

    @Autowired MockMvc mvc;
    @MockitoBean(name = "apiAuthenticationManager") AuthenticationManager authentication;
    @MockitoBean SessionLifecycle lifecycle;
    @MockitoBean NotificationService notifications;

    @ParameterizedTest
    @EnumSource(Role.class)
    void listsTheAuthenticatedRecipientsNotificationsAndUnreadCount(Role role) throws Exception {
        long recipient = (long) role.ordinal() + 1;
        var actor = principal(recipient, role);
        var unread = new NotificationItem(101, 12, "ORDER_ASSIGNED", "新维修任务待处理", "请查看工单。", null, CREATED_AT);
        var read = new NotificationItem(100, 12, "ORDER_SUBMITTED", "新报修待分配", "请查看工单。", READ_AT, CREATED_AT);
        when(notifications.list(recipient, 0, 20, false))
                .thenReturn(new NotificationPage(List.of(unread, read), 0, 20, 2, 1, 1));
        when(notifications.unreadCount(recipient)).thenReturn(1L);

        ok(mvc.perform(get(BASE).with(user(actor))))
                .andExpect(jsonPath("$.items[0].id").value("101"))
                .andExpect(jsonPath("$.items[0].orderId").value("12"))
                .andExpect(jsonPath("$.items[0].eventType").value("ORDER_ASSIGNED"))
                .andExpect(jsonPath("$.items[0].title").value("新维修任务待处理"))
                .andExpect(jsonPath("$.items[0].body").value("请查看工单。"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-26T15:00:01+08:00"))
                .andExpect(jsonPath("$.items[0].recipientId").doesNotExist())
                .andExpect(jsonPath("$.items[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.items[1].read").value(true))
                .andExpect(jsonPath("$.items[1].readAt").value("2026-11-26T15:05:01+08:00"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.unreadCount").value(1));
        ok(mvc.perform(get(BASE + "/unread-count").with(user(actor))))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void passesExplicitPaginationAndUnreadFilter() throws Exception {
        when(notifications.list(1, 2, 1, true)).thenReturn(new NotificationPage(List.of(), 2, 1, 2, 2, 2));

        ok(mvc.perform(get(BASE).param("page", "2").param("size", "1").param("unreadOnly", "true")
                .with(user(principal(1, Role.STUDENT)))))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void requiresAuthenticationForEveryEndpoint() throws Exception {
        problem(mvc.perform(get(BASE)), 401, "AUTH_REQUIRED");
        problem(mvc.perform(get(BASE + "/unread-count")), 401, "AUTH_REQUIRED");
        problem(mvc.perform(post(BASE + READ)), 401, "AUTH_REQUIRED");
        problem(mvc.perform(post(BASE + READ_ALL)), 401, "AUTH_REQUIRED");
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {READ, READ_ALL})
    void requiresOriginAndCsrfOnWrites(String path) throws Exception {
        var actor = principal(1, Role.STUDENT);
        problem(mvc.perform(post(BASE + path).with(user(actor)).with(csrf().asHeader())), 403, "ORIGIN_REJECTED");
        problem(mvc.perform(post(BASE + path).header("Origin", ORIGIN).with(user(actor))), 403, "CSRF_REJECTED");
        verifyNoInteractions(notifications);
    }

    @Test
    void mapsCrossRecipientMissingAndNonPositiveIdsToTheSameError() throws Exception {
        var owner = principal(1, Role.STUDENT);
        var visitor = principal(2, Role.WORKER);
        when(notifications.read(2, 101)).thenThrow(new NotificationNotFoundException());
        when(notifications.read(1, 999)).thenThrow(new NotificationNotFoundException());
        when(notifications.read(1, 0)).thenThrow(new NotificationNotFoundException());
        when(notifications.read(1, -1)).thenThrow(new NotificationNotFoundException());

        problem(mvc.perform(write(READ, visitor, new MockHttpSession())), 404, "NOTIFICATION_NOT_FOUND");
        for (long id : new long[]{999, 0, -1}) {
            problem(mvc.perform(write("/" + id + "/read", owner, new MockHttpSession())), 404, "NOTIFICATION_NOT_FOUND");
        }
        verify(notifications).read(2, 101);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,abc", "page,2147483648", "page,''", "size,0", "size,51", "unreadOnly,maybe",
            "unreadOnly,1", "unreadOnly,''"})
    void rejectsInvalidListParameters(String parameter, String value) throws Exception {
        problem(mvc.perform(get(BASE).param(parameter, value).with(user(principal(1, Role.STUDENT)))),
                400, "VALIDATION_ERROR");
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "0x65", "9223372036854775808"})
    void rejectsInvalidNotificationIds(String id) throws Exception {
        problem(mvc.perform(write("/" + id + "/read", principal(1, Role.STUDENT), new MockHttpSession())),
                400, "VALIDATION_ERROR");
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {READ, READ_ALL})
    void rejectsInvalidPostBodies(String path) throws Exception {
        var actor = principal(1, Role.STUDENT);
        for (String body : List.of("[]", "null", "true", "{\"recipientId\":2}", "{", "{}{}", "{} 1")) {
            problem(mvc.perform(write(path, actor, new MockHttpSession())
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "VALIDATION_ERROR");
        }
        verifyNoInteractions(notifications);
    }

    @Test
    void readReturnsTheSameTimestampForRetriesWithAllowedBodies() throws Exception {
        var actor = principal(1, Role.STUDENT);
        when(notifications.read(1, 101)).thenReturn(new ReadResult(101, READ_AT, 1));

        for (String body : List.of("", " \t\r\n", "{}")) {
            ok(mvc.perform(write(READ, actor, new MockHttpSession()).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(jsonPath("$.id").value("101"))
                    .andExpect(jsonPath("$.read").value(true))
                    .andExpect(jsonPath("$.readAt").value("2026-11-26T15:05:01+08:00"))
                    .andExpect(jsonPath("$.unreadCount").value(1));
        }
    }

    @Test
    void readAllReturnsZeroChangesOnRetry() throws Exception {
        var actor = principal(3, Role.ADMIN);
        when(notifications.readAll(3)).thenReturn(new ReadAllResult(2, 0), new ReadAllResult(0, 0));

        ok(mvc.perform(write(READ_ALL, actor, new MockHttpSession()).contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(jsonPath("$.markedCount").value(2))
                .andExpect(jsonPath("$.unreadCount").value(0));
        ok(mvc.perform(write(READ_ALL, actor, new MockHttpSession())))
                .andExpect(jsonPath("$.markedCount").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
        ok(mvc.perform(write(READ_ALL, actor, new MockHttpSession())
                .contentType(MediaType.APPLICATION_JSON).content(" \t\r\n")))
                .andExpect(jsonPath("$.markedCount").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @ParameterizedTest
    @CsvSource({READ + "," + READ_ALL, READ_ALL + "," + READ})
    void bothWritesShareTheSameRateLimit(String firstPath, String secondPath) throws Exception {
        var actor = principal(1, Role.STUDENT);
        var session = new MockHttpSession();
        when(notifications.read(1, 101)).thenReturn(new ReadResult(101, READ_AT, 0));
        when(notifications.readAll(1)).thenReturn(new ReadAllResult(0, 0));

        ok(mvc.perform(write(firstPath, actor, session)));
        problem(mvc.perform(write(secondPath, actor, session)), 429, "RATE_LIMITED")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void mapsDatabaseUnavailabilityToTheProblemContract() throws Exception {
        when(notifications.unreadCount(1)).thenThrow(new TransientDataAccessResourceException("fixture"));

        problem(mvc.perform(get(BASE + "/unread-count").with(user(principal(1, Role.STUDENT)))),
                503, "TEMPORARILY_UNAVAILABLE");
    }

    private static MockHttpServletRequestBuilder write(String path, CampusPrincipal actor, MockHttpSession session) {
        return post(BASE + path).session(session).header("Origin", ORIGIN).with(user(actor)).with(csrf().asHeader());
    }

    private static CampusPrincipal principal(long id, Role role) {
        var account = new User("fixture" + id, null, "Fixture", null, role, CREATED_AT);
        ReflectionTestUtils.setField(account, "id", id);
        return new CampusPrincipal(account);
    }

    private static ResultActions ok(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    private static ResultActions problem(ResultActions result, int expectedStatus, String code) throws Exception {
        return result.andExpect(status().is(expectedStatus))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }
}
