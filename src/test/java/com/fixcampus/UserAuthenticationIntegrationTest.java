package com.fixcampus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.DuplicateUsernameException;
import com.fixcampus.form.RegisterForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAuthenticationIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final String prefix = "it" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private final String password = "Fixture_" + UUID.randomUUID() + "!";
    private final Set<String> ownedNames = ConcurrentHashMap.newKeySet();
    private final EnumMap<Role, String> fixtureNames = new EnumMap<>(Role.class);
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeAll
    void createOwnedFixtures() {
        for (Role role : Role.values()) {
            String name = prefix + role.name().toLowerCase(Locale.ROOT);
            ownedNames.add(name);
            fixtureNames.put(role, name);
            users.saveAndFlush(new User(name, passwords.encode(password), "测试" + role.getLabel(), null,
                    role, LocalDateTime.now(clock)));
        }
    }

    @AfterAll
    void removeOnlyOwnedFixtures() {
        for (String name : ownedNames) {
            jdbc.update("DELETE FROM users WHERE username = ?", name);
        }
    }

    private String newUsername() {
        String name = prefix + sequence.incrementAndGet();
        ownedNames.add(name);
        return name;
    }

    private MockHttpServletRequestBuilder registration(String username) {
        return post("/register").with(csrf()).param("username", username)
                .param("password", password).param("confirmPassword", password)
                .param("realName", "  测试学生  ").param("phone", "   ");
    }

    private MockHttpSession login(Role role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", fixtureNames.get(role)).param("password", password))
                .andExpect(status().isFound()).andExpect(redirectedUrl(role.getHomePath()))
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void registrationNormalizesSafeFieldsHashesPasswordAndIgnoresSystemFields() throws Exception {
        String name = newUsername();
        mvc.perform(registration("  " + name.toUpperCase(Locale.ROOT) + "  ")
                        .param("role", "ADMIN").param("id", "1").param("createdAt", "2000-01-01T00:00:00"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?registered"));
        User saved = users.findByUsername(name).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.STUDENT);
        assertThat(saved.getRealName()).isEqualTo("测试学生");
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getCreatedAt()).isAfter(LocalDateTime.now(clock).minusMinutes(1));
        assertThat(saved.getPassword()).startsWith("{pbkdf2@SpringSecurity_v5_8}").isNotEqualTo(password);
        assertThat(passwords.matches(password, saved.getPassword())).isTrue();
        mvc.perform(get("/student/orders")).andExpect(status().isFound());
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("username", "abc", "username"),
                Arguments.of("username", "a".repeat(31), "username"),
                Arguments.of("username", "bad-name", "username"),
                Arguments.of("username", "中文账号", "username"),
                Arguments.of("username", " ", "username"),
                Arguments.of("password", "1234567", "password"),
                Arguments.of("password", "字".repeat(65), "password"),
                Arguments.of("password", "😀".repeat(65), "password"),
                Arguments.of("confirmPassword", "different-value", "passwordConfirmed"),
                Arguments.of("realName", "  ", "realName"),
                Arguments.of("realName", "😀".repeat(51), "realName"),
                Arguments.of("phone", "😀".repeat(31), "phone"));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidInputsReturn400WithoutWritingOrEchoingPasswords(String field, String value, String error) throws Exception {
        String name = newUsername();
        var parameters = new org.springframework.util.LinkedMultiValueMap<String, String>();
        parameters.set("username", name);
        parameters.set("password", password);
        parameters.set("confirmPassword", password);
        parameters.set("realName", "测试学生");
        parameters.set("phone", "");
        parameters.set(field, value);
        if (field.equals("password")) { parameters.set("confirmPassword", value); }
        mvc.perform(post("/register").with(csrf()).params(parameters))
                .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("registerForm", error))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        assertThat(users.existsByUsername(name)).isFalse();
    }

    @Test
    void accepts64UnicodeCodePointsAndDatabaseLengths() throws Exception {
        String name = newUsername();
        String unicodePassword = "😀".repeat(64);
        mvc.perform(post("/register").with(csrf()).param("username", name)
                        .param("password", unicodePassword).param("confirmPassword", unicodePassword)
                        .param("realName", "😀".repeat(50)).param("phone", "😀".repeat(30)))
                .andExpect(status().isSeeOther());
        User saved = users.findByUsername(name).orElseThrow();
        assertThat(passwords.matches(unicodePassword, saved.getPassword())).isTrue();
        assertThat(saved.getRealName().codePointCount(0, saved.getRealName().length())).isEqualTo(50);
        assertThat(saved.getPhone().codePointCount(0, saved.getPhone().length())).isEqualTo(30);
        mvc.perform(post("/login").with(csrf()).param("username", name).param("password", unicodePassword))
                .andExpect(redirectedUrl("/student/orders"));
    }

    @Test
    void preservesPasswordWhitespaceAndCase() throws Exception {
        String name = newUsername();
        String exact = "  Abcd5678  ";
        mvc.perform(post("/register").with(csrf()).param("username", name).param("realName", "测试学生")
                        .param("password", exact).param("confirmPassword", exact)).andExpect(status().isSeeOther());
        for (String changed : new String[]{exact.strip(), exact.toLowerCase(Locale.ROOT)}) {
            mvc.perform(post("/login").with(csrf()).param("username", name).param("password", changed))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").with(csrf()).param("username", name).param("password", exact))
                .andExpect(redirectedUrl("/student/orders"));
    }

    @Test
    void duplicateUsernameReturnsFieldErrorAndDoesNotChangeExistingUser() throws Exception {
        String name = fixtureNames.get(Role.ADMIN);
        String originalHash = users.findByUsername(name).orElseThrow().getPassword();
        mvc.perform(registration(name.toUpperCase(Locale.ROOT)))
                .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("registerForm", "username"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("该用户名已被使用")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        assertThat(users.findByUsername(name).orElseThrow().getPassword()).isEqualTo(originalHash);
        assertThat(users.findByUsername(name).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void concurrentDuplicateRegistrationsCommitExactlyOneAccount() throws Exception {
        String name = newUsername();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = Stream.generate(() -> executor.submit(() -> {
                start.await();
                var form = new RegisterForm();
                form.setUsername(name); form.setPassword(password); form.setConfirmPassword(password); form.setRealName("并发测试学生");
                try { service.register(form); return "created"; }
                catch (DuplicateUsernameException expected) { return "duplicate"; }
            })).limit(2).toList();
            start.countDown();
            assertThat(Arrays.asList(attempts.get(0).get(), attempts.get(1).get()))
                    .containsExactlyInAnyOrder("created", "duplicate");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, name)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void roleLoginNormalizesUsernameIgnoresForgedRoleAndRejectsCrossRoleRequests(Role role) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(csrf())
                        .param("username", " " + fixtureNames.get(role).toUpperCase(Locale.ROOT) + " ")
                        .param("password", password).param("role", "ADMIN").param("redirect", "/admin/orders"))
                .andExpect(redirectedUrl(role.getHomePath())).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get(role.getHomePath()).session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("测试" + role.getLabel())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("{pbkdf2"))));
        for (Role other : Role.values()) {
            if (other != role) {
                mvc.perform(get(other.getHomePath()).session(session)).andExpect(status().isForbidden());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void authenticatedPublicEntrancesReturnToOwnRoleAndRegistrationIsForbidden(Role role) throws Exception {
        var session = login(role);
        for (String path : new String[]{"/", "/login", "/register"}) {
            mvc.perform(get(path).session(session)).andExpect(redirectedUrl(role.getHomePath()));
        }
        String name = newUsername();
        mvc.perform(registration(name).session(session)).andExpect(status().isForbidden());
        assertThat(users.existsByUsername(name)).isFalse();
    }

    @Test
    void anonymousRequestsRedirectToLoginAndUnknownRoutesAreNotPublic() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        for (Role role : Role.values()) {
            mvc.perform(get(role.getHomePath())).andExpect(status().isFound())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
        }
        mvc.perform(get("/unexpected-path")).andExpect(status().isFound());
    }

    @Test
    void wrongUnknownAndEmptyCredentialsShareOneFailureAndCreateNoAuthentication() throws Exception {
        for (String name : new String[]{fixtureNames.get(Role.STUDENT), newUsername(), ""}) {
            var result = mvc.perform(post("/login").with(csrf()).param("username", name).param("password", "wrong-value"))
                    .andExpect(redirectedUrl("/login?error")).andReturn();
            var session = result.getRequest().getSession(false);
            assertThat(session == null ? null : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        }
        mvc.perform(get("/login?error")).andExpect(content().string(org.hamcrest.Matchers.containsString("用户名或密码错误")));
    }

    @Test
    void loginChangesSessionIdAndErasesCredentialHashFromSessionPrincipal() throws Exception {
        var before = (MockHttpSession) mvc.perform(get("/login")).andReturn().getRequest().getSession(false);
        String previousId = before.getId();
        var after = mvc.perform(post("/login").session(before).with(csrf())
                        .param("username", fixtureNames.get(Role.STUDENT)).param("password", password))
                .andExpect(status().isFound()).andReturn().getRequest().getSession(false);
        assertThat(after.getId()).isNotEqualTo(previousId);
        var context = (SecurityContext) after.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(((CampusPrincipal) context.getAuthentication().getPrincipal()).getPassword()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文账号", "école", "😀😀😀😀", "abc", "abcdefghijklmnopqrstuvwxyz12345", "bad-name", "   "})
    void invalidLoginUsernamesReturnCredentialFailureWithoutAuthentication(String username) throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).param("username", username)
                        .param("password", "Synthetic_Test_123"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error")).andReturn();
        var session = result.getRequest().getSession(false);
        assertThat(session == null ? null : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    @Test
    void csrfProtectsAllPublicWritesAndLogoutInvalidatesOnlyCurrentSession() throws Exception {
        for (String path : new String[]{"/register", "/login", "/logout"}) {
            mvc.perform(post(path)).andExpect(status().isForbidden());
        }
        var session = login(Role.STUDENT);
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/logout").session(session)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/student/orders").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/student/orders")).andExpect(status().isFound());
    }

    @Test
    void displayNameIsEscapedAndStudentOrderFormIsAvailableAfterStage42() throws Exception {
        String name = newUsername();
        mvc.perform(post("/register").with(csrf()).param("username", name).param("password", password)
                .param("confirmPassword", password).param("realName", "<script>alert(1)</script>")).andExpect(status().isSeeOther());
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", name).param("password", password))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/student/orders").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))));
        mvc.perform(get("/student/orders/new").session(session)).andExpect(status().isOk());
        mvc.perform(post("/student/orders").session(session).with(csrf())).andExpect(status().isBadRequest());
    }
}
