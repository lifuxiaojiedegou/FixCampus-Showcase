package com.fixcampus;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("web-slice")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderOperationsApiIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    CampusPrincipal student, other, admin, worker, otherWorker;
    final String password = UUID.randomUUID().toString();
    static final String ORIGIN = "http://127.0.0.1:5173";
    record Browser(MockHttpSession session, String token) { }

    @BeforeAll void fixtures() {
        String prefix = "ops" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "s", Role.STUDENT); other = fixture(prefix + "o", Role.STUDENT);
        admin = fixture(prefix + "a", Role.ADMIN); worker = fixture(prefix + "w", Role.WORKER);
        otherWorker = fixture(prefix + "x", Role.WORKER);
    }
    CampusPrincipal fixture(String name, Role role) {
        var user = users.saveAndFlush(new User(name, passwords.encode(password), "虚构业务测试", null, role, LocalDateTime.now()));
        var principal = new CampusPrincipal(user); principal.eraseCredentials(); return principal;
    }
    @AfterEach void cleanOrders() {
        for (var actor : List.of(student, other)) {
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", actor.getId());
            removeStudentOrderFixtures(jdbc, actor.getId());
        }
    }
    @AfterAll void cleanUsers() {
        cleanOrders();
        List.of(student, other, admin, worker, otherWorker).forEach(actor -> removeUserFixture(jdbc, actor.getId()));
    }
    JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString()); }
    Browser csrf(MockHttpSession session) throws Exception {
        var req = get("/api/v1/auth/csrf"); if (session != null) req.session(session);
        var r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return new Browser((MockHttpSession) r.getRequest().getSession(false), json(r).get("token").asText());
    }
    Browser login(CampusPrincipal actor) throws Exception {
        var b = csrf(null);
        mvc.perform(post("/api/v1/auth/login").session(b.session).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", b.token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", actor.getUsername(), "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.getId().toString()));
        return csrf(b.session);
    }
    ResultActions write(Browser b, String path, String body) throws Exception {
        return mvc.perform(post(path).session(b.session).header("Origin", ORIGIN).header("X-CSRF-TOKEN", b.token)
                .contentType("application/json").content(body));
    }
    String path(String id, String action) { return "/api/v1/orders/" + id + "/" + action; }
    String assignedTo(CampusPrincipal w) { return "{\"workerId\":\"" + w.getId() + "\"}"; }
    String create(CampusPrincipal actor, String location) throws Exception {
        var r = writeWithKey(login(actor), mapper.writeValueAsString(Map.of("location", location,
                "category", "OTHER", "description", "虚构接口闭环")));
        return json(r).get("id").asText();
    }
    MvcResult writeWithKey(Browser b, String body) throws Exception {
        return mvc.perform(post("/api/v1/orders").session(b.session).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", b.token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
    }
    void assign(String id) throws Exception { write(login(admin), path(id,"assign"), assignedTo(worker)).andExpect(status().isNoContent()); }
    void start(String id) throws Exception { write(login(worker), path(id,"start"), "{}").andExpect(status().isNoContent()); }
    void complete(String id) throws Exception { write(login(worker), path(id,"complete"), "{\"repairResult\":\"已修复\"}").andExpect(status().isNoContent()); }
    long events(String id, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id=? AND action=?", Long.class, id, action);
    }

    @Test void realSessionsCompleteThreeRoleWorkflowAndExposeSafeDetails() throws Exception {
        var s = login(student); String id = create(student, "虚构闭环 A101");
        mvc.perform(get("/api/v1/admin/orders").session(login(admin).session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        assign(id);
        mvc.perform(get("/api/v1/worker/orders/"+id).session(login(worker).session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableActions[0]").value("START"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.studentUsername").doesNotExist());
        start(id); complete(id);
        mvc.perform(get("/api/v1/orders/"+id).session(s.session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.repairResult").value("已修复")).andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.availableActions[0]").value("CONFIRM"));
        write(s, path(id,"confirm"), "{}").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/orders/"+id).session(s.session)).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty()).andExpect(jsonPath("$.history.incomplete").value(false))
                .andExpect(jsonPath("$.history.entries.length()").value(5));
        mvc.perform(get("/api/v1/admin/orders/"+id).session(login(admin).session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentUsername").value(student.getUsername()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        for (String action : List.of("SUBMITTED","ASSIGNED","REPAIR_STARTED","REPAIR_COMPLETED","CONFIRMED"))
            assertThat(events(id, action)).isEqualTo(1);
    }

    @Test void webSliceRootRedirectsAndDisabledRegistrationLeavesUsersUnchanged() throws Exception {
        long before = users.count();
        String name = "closed_" + UUID.randomUUID().toString().substring(0, 12);
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/app/"));
        try {
            for (var session : Arrays.asList(null, login(student).session)) {
                var page = get("/register");
                var submit = post("/register").with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("username", name).param("realName", "虚构注册测试")
                        .param("password", password).param("confirmPassword", password);
                if (session != null) {
                    page.session(session);
                    submit.session(session);
                }
                mvc.perform(page).andExpect(status().isForbidden());
                mvc.perform(submit).andExpect(status().isForbidden());
                assertThat(users.count()).isEqualTo(before);
                assertThat(users.findByUsername(name)).isEmpty();
            }
        } finally {
            users.findByUsername(name).ifPresent(users::delete);
        }
    }

    @Test void mysqlLockWaitReturns503WithoutPartialWritesAndLaterStateConflictReturns409() throws Exception {
        String id = create(student, "虚构锁超时测试");
        var browser = login(student);
        String cancel = path(id, "cancel");
        var orderBefore = jdbc.queryForMap("SELECT * FROM repair_orders WHERE id=?", id);
        var eventsBefore = jdbc.queryForList("SELECT * FROM order_events WHERE order_id=? ORDER BY id", id);
        try (var executor = Executors.newSingleThreadExecutor();
                var blocker = dataSource.getConnection();
                var lock = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
            // Borrow a separate pool connection while the lock holder owns its connection.
            assertThat(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isEqualTo(3);
            blocker.setAutoCommit(false);
            try {
                lock.setLong(1, Long.parseLong(id));
                try (var row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
                long started = System.nanoTime();
                executor.submit(() -> {
                    var result = write(browser, cancel, "{}").andExpect(status().isServiceUnavailable())
                            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                            .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE")).andReturn();
                    String requestId = result.getResponse().getHeader("X-Request-Id");
                    assertThat(requestId).isNotBlank().isEqualTo(json(result).path("requestId").asText());
                    assertThat(result.getResolvedException()).isNotNull();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(result.getResolvedException()))
                            .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(1205));
                    return null;
                }).get(10, TimeUnit.SECONDS);
                assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(2));
            } finally {
                blocker.rollback();
            }
        }
        assertThat(jdbc.queryForMap("SELECT * FROM repair_orders WHERE id=?", id)).isEqualTo(orderBefore);
        assertThat(jdbc.queryForList("SELECT * FROM order_events WHERE order_id=? ORDER BY id", id)).isEqualTo(eventsBefore);
        write(browser, cancel, "{}").andExpect(status().isNoContent());
        write(browser, cancel, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
        assertThat(events(id, "CANCELLED")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"assign","start","complete","confirm","cancel"})
    void anonymousAndMissingCsrfAreDistinct(String action) throws Exception {
        String id = create(student,"虚构拒绝");
        mvc.perform(post(path(id,action)).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        var actor = switch (action) {
            case "assign" -> admin;
            case "confirm", "cancel" -> student;
            default -> worker;
        };
        var b = login(actor);
        mvc.perform(post(path(id,action)).session(b.session).header("Origin",ORIGIN).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(events(id,"ASSIGNED")).isZero();
    }

    @Test void roleAndObjectPermissionsCannotBeChosenByClient() throws Exception {
        String id = create(student,"虚构归属"); var s=login(student); var w=login(worker); var x=login(otherWorker); var o=login(other);
        write(s,path(id,"assign"),assignedTo(worker)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/orders").session(s.session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/worker/orders").session(s.session)).andExpect(status().isForbidden());
        assign(id);
        for (String action : List.of("start","complete"))
            write(x,path(id,action),"{}").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/v1/worker/orders/"+id).session(x.session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/"+id).session(o.session)).andExpect(status().isNotFound());
        write(o,path(id,"confirm"),"{}").andExpect(status().isNotFound());
        write(w,path(id,"confirm"),"{}").andExpect(status().isForbidden());
        assertThat(events(id,"REPAIR_STARTED")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"assign,STUDENT","assign,WORKER","start,STUDENT","start,ADMIN",
            "complete,STUDENT","complete,ADMIN","confirm,WORKER","confirm,ADMIN","cancel,WORKER","cancel,ADMIN"})
    void everyMutationRejectsBothWrongRoles(String action, Role role) throws Exception {
        String id=create(student,"虚构角色矩阵");
        var actor=role==Role.STUDENT?student:role==Role.ADMIN?admin:worker;
        write(login(actor),path(id,action),payload(action)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        assertThat(events(id,"ASSIGNED")).isZero();
    }

    @Test void allOperationalReadsRequireAuthenticationAndMissingObjectsStay404() throws Exception {
        for(String url:List.of("/api/v1/admin/orders","/api/v1/admin/orders/1",
                "/api/v1/worker/orders","/api/v1/worker/orders/1"))
            mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/v1/admin/orders/0").session(login(admin).session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/worker/orders/0").session(login(worker).session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/orders/not-a-number").session(login(admin).session)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/admin/orders,STUDENT","/api/v1/admin/orders,WORKER",
            "/api/v1/admin/orders/1,STUDENT","/api/v1/admin/orders/1,WORKER",
            "/api/v1/worker/orders,STUDENT","/api/v1/worker/orders,ADMIN",
            "/api/v1/worker/orders/1,STUDENT","/api/v1/worker/orders/1,ADMIN",
            "/api/v1/orders,WORKER","/api/v1/orders,ADMIN",
            "/api/v1/orders/1,WORKER","/api/v1/orders/1,ADMIN"})
    void readEndpointsRejectEveryWrongRole(String url, Role role) throws Exception {
        var actor=role==Role.STUDENT?student:role==Role.ADMIN?admin:worker;
        mvc.perform(get(url).session(login(actor).session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
    }

    @Test void workerDirectoryIsAdminOnlyAndUsesDatabaseRoleWithMinimalFields() throws Exception {
        mvc.perform(get("/api/v1/admin/workers")).andExpect(status().isUnauthorized());
        for(var actor:List.of(student,worker))
            mvc.perform(get("/api/v1/admin/workers").session(login(actor).session)).andExpect(status().isForbidden());
        var a=login(admin);
        var result=mvc.perform(get("/api/v1/admin/workers").session(a.session)).andExpect(status().isOk()).andReturn();
        var directory=json(result);
        assertThat(directory).hasSize(2);
        var ids=new HashSet<String>();
        directory.forEach(w->{assertThat(w.size()).isEqualTo(3);ids.add(w.get("id").asText());});
        assertThat(ids).containsExactlyInAnyOrder(worker.getId().toString(),otherWorker.getId().toString());
        assertThat(directory.get(0).get("username").asText()).isLessThan(directory.get(1).get("username").asText());
        jdbc.update("UPDATE users SET role='STUDENT' WHERE id=?",otherWorker.getId());
        try {
            mvc.perform(get("/api/v1/admin/workers").session(a.session)).andExpect(jsonPath("$.length()").value(1));
            String id=create(student,"虚构资格复核");
            write(a,path(id,"assign"),assignedTo(otherWorker)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_WORKER"));
        } finally {jdbc.update("UPDATE users SET role='WORKER' WHERE id=?",otherWorker.getId());}
    }

    @Test void optionalRepairResultRetainsOriginalServiceContract() throws Exception {
        String id=create(student,"虚构可选说明");assign(id);start(id);
        write(login(worker),path(id,"complete"),"{}").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/orders/"+id).session(login(student).session))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.repairResult").isEmpty());
    }

    @Test void assignmentRejectsInvalidWorkerAndServerOwnedFields() throws Exception {
        String id=create(student,"虚构输入");var a=login(admin);
        for(String body:List.of("{}","{\"workerId\":1.5}","{\"workerId\":true}","{\"workerId\":\"0\"}",
                "{\"workerId\":\"9223372036854775808\"}","{\"workerId\":null}","[]"))
            write(a,path(id,"assign"),body).andExpect(status().isBadRequest());
        write(a,path(id,"assign"),assignedTo(student)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WORKER"));
        for(String field:List.of("actorId","studentId","status","createdAt"))
            write(a,path(id,"assign"),"{\"workerId\":\""+worker.getId()+"\",\""+field+"\":\"forged\"}")
                    .andExpect(status().isBadRequest());
        assertThat(events(id,"ASSIGNED")).isZero();
    }

    @Test void repairResultUsesExistingUnicodeAndNormalizationRules() throws Exception {
        String id=create(student,"虚构说明");assign(id);start(id);var w=login(worker);
        write(w,path(id,"complete"),"{\"repairResult\":123}").andExpect(status().isBadRequest());
        write(w,path(id,"complete"),mapper.writeValueAsString(Map.of("repairResult","😀".repeat(1001))))
                .andExpect(status().isBadRequest());
        write(w,path(id,"complete"),"{\"repairResult\":\"ok\",\"status\":\"COMPLETED\"}").andExpect(status().isBadRequest());
        write(w,path(id,"complete"),mapper.writeValueAsString(Map.of("repairResult"," \r\n"+"😀".repeat(1000)+"\r\n ")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT repair_result FROM repair_orders WHERE id=?",String.class,id)).isEqualTo("😀".repeat(1000));
    }

    @Test void emptyCommandsRejectForgedFieldsAndPrematureTransitionsReturn409() throws Exception {
        String id=create(student,"虚构状态");var s=login(student);var w=login(worker);
        write(s,path(id,"confirm"),"{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
        write(s,path(id,"confirm"),"{\"studentId\":\"1\"}").andExpect(status().isBadRequest());
        assign(id);
        write(w,path(id,"start"),"{\"actorId\":\"1\"}").andExpect(status().isBadRequest());
        write(w,path(id,"start"),"null").andExpect(status().isBadRequest());
        write(w,path(id,"complete"),"{}").andExpect(status().isConflict());
        start(id);write(w,path(id,"start"),"{}").andExpect(status().isConflict());
        complete(id);write(w,path(id,"complete"),"{}").andExpect(status().isConflict());
        write(login(admin),path(id,"assign"),assignedTo(otherWorker)).andExpect(status().isConflict());
    }

    @Test void listsPageInDatabaseAndRestrictWorkerScopeAndSearch() throws Exception {
        String first=create(student,"虚构百分%_标记");String second=create(other,"虚构普通");
        jdbc.update("UPDATE repair_orders SET created_at='2026-11-08 12:00:00.000000' WHERE id IN (?,?)",first,second);
        assign(first);write(login(admin),path(second,"assign"),assignedTo(otherWorker)).andExpect(status().isNoContent());
        var a=login(admin);var w=login(worker);
        mvc.perform(get("/api/v1/admin/orders").session(a.session).param("size","1").param("sort","createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(first));
        mvc.perform(get("/api/v1/admin/orders").session(a.session).param("size","1").param("page","1").param("sort","createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second));
        mvc.perform(get("/api/v1/admin/orders").session(a.session).param("q","%_"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(first));
        mvc.perform(get("/api/v1/worker/orders").session(w.session).param("q",second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/worker/orders").session(w.session))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(first));
        for (String[] p : List.of(new String[]{"size","51"},new String[]{"page","-1"},new String[]{"sort","id,asc"},new String[]{"status","forged"},new String[]{"q","x".repeat(121)}))
            mvc.perform(get("/api/v1/admin/orders").session(a.session).param(p[0],p[1])).andExpect(status().isBadRequest());
    }

    String prepare(String action) throws Exception {
        String id = create(student, "虚构事务");
        if (!List.of("assign", "cancel").contains(action)) assign(id);
        if (List.of("complete", "confirm").contains(action)) start(id);
        if (action.equals("confirm")) complete(id);
        return id;
    }
    Browser operator(String action) throws Exception {
        return login(switch (action) {
            case "assign" -> admin;
            case "confirm", "cancel" -> student;
            default -> worker;
        });
    }
    String payload(String action) {return action.equals("assign")?assignedTo(worker):action.equals("complete")?"{\"repairResult\":\"并发结果\"}":"{}";}

    @ParameterizedTest @ValueSource(strings={"assign","start","complete","confirm","cancel"})
    void concurrentTransitionHasOneWinnerAndOneEvent(String action) throws Exception {
        String id=prepare(action);var a=operator(action);var b=operator(action);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=List.of(a,b).stream().map(browser->pool.submit(()->{gate.await();return write(browser,path(id,action),payload(action)).andReturn().getResponse().getStatus();})).toList();
            gate.countDown();var statuses=new ArrayList<Integer>();for(var task:tasks)statuses.add(task.get(20,TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(204,409);
        }
        String event = switch (action) {
            case "assign" -> "ASSIGNED";
            case "start" -> "REPAIR_STARTED";
            case "complete" -> "REPAIR_COMPLETED";
            case "cancel" -> "CANCELLED";
            default -> "CONFIRMED";
        };
        assertThat(events(id,event)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"assign","start","complete","confirm","cancel"})
    void outerRollbackRevertsStateAndEventTogether(String action) throws Exception {
        String id=prepare(action);var b=operator(action);
        var before=jdbc.queryForMap("SELECT status,worker_id,repair_result,completed_at FROM repair_orders WHERE id=?",id);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id=?",Long.class,id);
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            try{write(b,path(id,action),payload(action)).andExpect(status().isNoContent());}catch(Exception e){throw new RuntimeException(e);}
            throw new IllegalStateException("deliberate rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("deliberate rollback");
        assertThat(jdbc.queryForMap("SELECT status,worker_id,repair_result,completed_at FROM repair_orders WHERE id=?",id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id=?",Long.class,id)).isEqualTo(count);
    }

    @ParameterizedTest @ValueSource(strings={"{}", ""})
    void studentCancellationUsesExistingStateAndEventContract(String body) throws Exception {
        String id = create(student, "虚构撤销");
        var browser = login(student);
        write(browser, path(id, "cancel"), body).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/orders/" + id).session(browser.session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.availableActions").isEmpty())
                .andExpect(jsonPath("$.history.entries[1].action").value("CANCELLED"));
        write(browser, path(id, "cancel"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
        assertThat(events(id, "CANCELLED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT worker_id FROM repair_orders WHERE id=?", Long.class, id)).isNull();
    }

    @Test void cancellationRejectsForeignMissingMalformedAndForgedInputWithoutMutation() throws Exception {
        String id = create(student, "虚构撤销边界");
        write(login(other), path(id, "cancel"), "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        var browser = login(student);
        write(browser, path("0", "cancel"), "{}").andExpect(status().isNotFound());
        write(browser, path(Long.toString(Long.MAX_VALUE), "cancel"), "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        write(browser, path("not-a-number", "cancel"), "{}").andExpect(status().isBadRequest());
        for (String body : List.of("[]", "null", "{\"studentId\":\"1\"}", "{\"status\":\"CANCELLED\"}"))
            write(browser, path(id, "cancel"), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(path(id, "cancel")).session(browser.session).header("Origin", "https://foreign.invalid")
                .header("X-CSRF-TOKEN", browser.token).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
        assertThat(events(id, "CANCELLED")).isZero();
        mvc.perform(get("/api/v1/orders/" + id).session(browser.session))
                .andExpect(jsonPath("$.status").value("PENDING_ASSIGNMENT"));
        assign(id);
        write(browser, path(id, "cancel"), "{}").andExpect(status().isConflict());
        start(id);
        write(browser, path(id, "cancel"), "{}").andExpect(status().isConflict());
        complete(id);
        write(browser, path(id, "cancel"), "{}").andExpect(status().isConflict());
        write(browser, path(id, "confirm"), "{}").andExpect(status().isNoContent());
        write(browser, path(id, "cancel"), "{}").andExpect(status().isConflict());
        assertThat(events(id, "CANCELLED")).isZero();
    }

    @Test void cancellationAndAssignmentRaceKeepsOneLegalStateAndEvent() throws Exception {
        String id = create(student, "虚构撤销派单竞争");
        var s = login(student);
        var a = login(admin);
        var gate = new CountDownLatch(1);
        int cancelled, assigned;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancelTask = pool.submit(() -> {
                gate.await();
                return write(s, path(id, "cancel"), "{}").andReturn().getResponse().getStatus();
            });
            var assignTask = pool.submit(() -> {
                gate.await();
                return write(a, path(id, "assign"), assignedTo(worker)).andReturn().getResponse().getStatus();
            });
            gate.countDown();
            cancelled = cancelTask.get(20, TimeUnit.SECONDS);
            assigned = assignTask.get(20, TimeUnit.SECONDS);
        }
        assertThat(List.of(cancelled, assigned)).containsExactlyInAnyOrder(204, 409);
        var row = jdbc.queryForMap("SELECT status,worker_id FROM repair_orders WHERE id=?", id);
        if (cancelled == 204) {
            assertThat(row.get("status")).isEqualTo("CANCELLED");
            assertThat(row.get("worker_id")).isNull();
            assertThat(events(id, "CANCELLED")).isEqualTo(1);
            assertThat(events(id, "ASSIGNED")).isZero();
        } else {
            assertThat(row.get("status")).isEqualTo("PENDING_REPAIR");
            assertThat(((Number) row.get("worker_id")).longValue()).isEqualTo(worker.getId());
            assertThat(events(id, "CANCELLED")).isZero();
            assertThat(events(id, "ASSIGNED")).isEqualTo(1);
        }
    }
}
