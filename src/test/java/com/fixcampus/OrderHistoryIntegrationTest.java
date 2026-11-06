package com.fixcampus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fixcampus.entity.*;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderHistoryIntegrationTest extends RealMySqlTestSupport {
    @Autowired RepairOrderService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;
    @MockitoSpyBean OrderEventRepository events;
    private final List<Long> ownedUsers = new ArrayList<>();
    private CampusPrincipal student, stranger, admin, worker, otherWorker;

    @BeforeAll void fixtures() {
        String prefix = "hist_" + UUID.randomUUID().toString().substring(0, 8);
        student = account(prefix + "_s", Role.STUDENT); stranger = account(prefix + "_s2", Role.STUDENT);
        admin = account(prefix + "_a", Role.ADMIN); worker = account(prefix + "_w", Role.WORKER);
        otherWorker = account(prefix + "_w2", Role.WORKER);
    }

    private CampusPrincipal account(String name, Role role) {
        var saved = users.saveAndFlush(new User(name, passwords.encode(UUID.randomUUID().toString()),
                "记录测试" + role.getLabel(), null, role, LocalDateTime.now(clock)));
        ownedUsers.add(saved.getId()); var principal = new CampusPrincipal(saved); principal.eraseCredentials(); return principal;
    }

    @AfterEach void cleanOrders() {
        reset(events);
        for (Long id : ownedUsers) jdbc.update("DELETE FROM repair_orders WHERE student_id=?", id);
    }
    @AfterAll void cleanUsers() {
        cleanOrders(); for (Long id : ownedUsers) jdbc.update("DELETE FROM users WHERE id=?", id);
    }

    private Long create() {
        var form = new RepairOrderForm(); form.setLocation("处理记录测试地点"); form.setCategory(RepairCategory.OTHER);
        form.setDescription("处理记录测试描述"); return service.createForStudent(student, form);
    }
    private CompleteRepairForm result(String value) { var form = new CompleteRepairForm(); form.setRepairResult(value); return form; }
    private Long prepare(OrderAction action) {
        Long id = create();
        if (action == OrderAction.REPAIR_STARTED || action == OrderAction.REPAIR_COMPLETED || action == OrderAction.CONFIRMED) service.assign(admin, id, worker.getId());
        if (action == OrderAction.REPAIR_COMPLETED || action == OrderAction.CONFIRMED) service.start(worker, id);
        if (action == OrderAction.CONFIRMED) service.complete(worker, id, result("已处理"));
        return id;
    }
    private List<java.util.Map<String,Object>> rows(Long id) { return jdbc.queryForList("SELECT * FROM order_events WHERE order_id=? ORDER BY id", id); }

    @Test void fullLifecycleRecordsServerTimeActorAndDetailsForAllThreeViews() throws Exception {
        var earliest = LocalDateTime.now(clock).minusSeconds(1);
        Long id = create();
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf())
                        .param("workerId", worker.getId().toString()).param("actorId", stranger.getId().toString())
                        .param("actorName", "伪造姓名").param("occurredAt", "2099-01-01T00:00:00").param("action", "CANCELLED"))
                .andExpect(status().isSeeOther());
        service.start(worker, id); service.complete(worker, id, result("已修复\r\n<script>记录</script>")); service.confirm(student, id);
        var detail = service.getForStudent(student, id);
        var history = detail.history();
        assertThat(history.incomplete()).isFalse();
        assertThat(history.entries()).extracting(e -> e.action()).containsExactly(OrderAction.SUBMITTED, OrderAction.ASSIGNED,
                OrderAction.REPAIR_STARTED, OrderAction.REPAIR_COMPLETED, OrderAction.CONFIRMED);
        assertThat(history.entries()).allSatisfy(e -> {
            assertThat(e.recorded()).isTrue(); assertThat(e.occurredAt()).isBetween(earliest, LocalDateTime.now(clock).plusSeconds(1));
        });
        assertThat(history.entries()).extracting(e -> e.actorRole()).containsExactly(Role.STUDENT, Role.ADMIN, Role.WORKER, Role.WORKER, Role.STUDENT);
        assertThat(history.entries().get(1).actorName()).isEqualTo(admin.getRealName());
        assertThat(history.entries().getFirst().occurredAt()).isEqualTo(detail.createdAt());
        assertThat(history.entries().getLast().occurredAt()).isEqualTo(detail.completedAt());
        assertThat(history.entries().get(3).detail()).isEqualTo("已修复\n<script>记录</script>");
        assertThat(service.getForAdmin(admin, id).history()).isEqualTo(history);
        assertThat(service.getForWorker(worker, id).history()).isEqualTo(history);
        for (var actor : List.of(student, admin, worker)) {
            String prefix = actor.getRole() == Role.STUDENT ? "/student" : actor.getRole() == Role.ADMIN ? "/admin" : "/worker";
            mvc.perform(get(prefix + "/orders/" + id).with(user(actor))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("工单处理记录")))
                    .andExpect(content().string(containsString("&lt;script&gt;记录&lt;/script&gt;")))
                    .andExpect(content().string(not(containsString("<script>记录</script>"))))
                    .andExpect(content().string(not(containsString("伪造姓名"))));
        }
    }

    @Test void cancellationIsRecordedOnceAndRejectedOperationsAppendNothing() throws Exception {
        Long id = create();
        service.cancel(student, id); var before = rows(id);
        assertThat(before).extracting(row -> row.get("action")).containsExactly("SUBMITTED", "CANCELLED");
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", worker.getId().toString())).andExpect(status().isConflict());
        assertThat(rows(id)).isEqualTo(before);
    }

    @Test void foreignOrUnauthorizedRequestsCannotReadHistoryOrAppendIt() throws Exception {
        Long id = prepare(OrderAction.REPAIR_COMPLETED); var before = rows(id); clearInvocations(events);
        mvc.perform(get("/student/orders/" + id).with(user(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/worker/orders/" + id).with(user(otherWorker))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/orders/" + id).with(user(student))).andExpect(status().isForbidden());
        verify(events, never()).findByOrder_IdOrderByIdAsc(any());
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(otherWorker)).with(csrf()).param("repairResult", "越权记录")).andExpect(status().isNotFound());
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker))).andExpect(status().isForbidden());
        assertThat(rows(id)).isEqualTo(before);
    }

    @Test void legacyStatesShowOnlyKnownTimesAndNeverBackfillMissingEvents() throws Exception {
        for (RepairStatus state : RepairStatus.values()) {
            Long id = create(); jdbc.update("DELETE FROM order_events WHERE order_id=?", id);
            boolean assigned = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
            var completed = state == RepairStatus.COMPLETED ? LocalDateTime.of(2026,10,1,10,0) : null;
            jdbc.update("UPDATE repair_orders SET status=?,worker_id=?,created_at='2026-10-01 09:00:00',completed_at=? WHERE id=?",
                    state.name(), assigned ? worker.getId() : null, completed, id);
            var history = service.getForStudent(student,id).history();
            assertThat(history.incomplete()).isTrue();
            assertThat(history.entries()).hasSize(OrderAction.expectedFor(state).size());
            assertThat(history.entries().getFirst().occurredAt()).isEqualTo(LocalDateTime.of(2026,10,1,9,0));
            assertThat(history.entries()).allSatisfy(e -> {
                assertThat(e.recorded()).isFalse(); assertThat(e.actorName()).isNull();
                if (e.action()!=OrderAction.SUBMITTED && e.action()!=OrderAction.CONFIRMED) assertThat(e.occurredAt()).isNull();
            });
            if(completed!=null)assertThat(history.entries().getLast().occurredAt()).isEqualTo(completed);
            mvc.perform(get("/student/orders/"+id).with(user(student))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("部分历史操作未保存完整记录")));
            assertThat(rows(id)).isEmpty();
        }
    }

    @Test void legacyOrderCanContinueWithRealNewEventsAndSnapshotNames() {
        Long id=create();jdbc.update("DELETE FROM order_events WHERE order_id=?",id);
        service.assign(admin,id,worker.getId());service.start(worker,id);
        jdbc.update("UPDATE users SET real_name=? WHERE id=?", "更新后的管理员姓名", admin.getId());
        try {
            var history=service.getForStudent(student,id).history();
            assertThat(history.incomplete()).isTrue();assertThat(history.entries().getFirst().recorded()).isFalse();
            assertThat(history.entries().get(1).actorName()).isEqualTo(admin.getRealName());
            assertThat(history.entries().get(2).recorded()).isTrue();assertThat(rows(id)).hasSize(2);
        } finally {jdbc.update("UPDATE users SET real_name=? WHERE id=?",admin.getRealName(),admin.getId());}
    }

    @Test void storageFailureRollsBackEveryMutationWithoutSuccessRedirect() throws Exception {
        for(var action:OrderAction.values()){
            Long id=action==OrderAction.SUBMITTED?null:prepare(action);
            var before=id==null?List.of():rows(id);
            var orderBefore=id==null?null:jdbc.queryForMap("SELECT * FROM repair_orders WHERE id=?",id);
            long orderCount=jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders",Long.class);
            doThrow(new DataAccessResourceFailureException("private-history-storage")).when(events).saveAndFlush(any(OrderEvent.class));
            try {
                String route=switch(action){case SUBMITTED->"/student/orders";case ASSIGNED->"/admin/orders/"+id+"/assign";
                    case REPAIR_STARTED->"/worker/orders/"+id+"/start";case REPAIR_COMPLETED->"/worker/orders/"+id+"/complete";
                    case CONFIRMED->"/student/orders/"+id+"/confirm";case CANCELLED->"/student/orders/"+id+"/cancel";};
                var actor=action==OrderAction.ASSIGNED?admin:action==OrderAction.REPAIR_STARTED||action==OrderAction.REPAIR_COMPLETED?worker:student;
                mvc.perform(post(route).with(user(actor)).with(csrf()).param("location","记录失败测试").param("category","OTHER")
                                .param("description","记录失败测试").param("workerId",worker.getId().toString()).param("repairResult","记录失败测试"))
                        .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Location"))
                        .andExpect(content().string(not(containsString("private-history-storage"))));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders",Long.class)).isEqualTo(orderCount);
                if(id!=null){assertThat(rows(id)).isEqualTo(before);assertThat(jdbc.queryForMap("SELECT * FROM repair_orders WHERE id=?",id)).isEqualTo(orderBefore);}
            } finally {reset(events);}
        }
    }

    @Test void explicitTransactionRollbackRestoresStatusAndFlushedEventTogether() {
        Long id=prepare(OrderAction.REPAIR_COMPLETED);var before=service.getForStudent(student,id);
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            service.complete(worker,id,result("将回滚的结果"));entities.flush();assertThat(rows(id)).hasSize(4);
            throw new IllegalStateException("Force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.getForStudent(student,id)).isEqualTo(before);assertThat(rows(id)).hasSize(3);
    }

    @Test void simultaneousConfirmationsAppendExactlyOneConfirmation() throws Exception {
        Long id=prepare(OrderAction.CONFIRMED);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->confirm(gate,id));var second=pool.submit(()->confirm(gate,id));gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("success","conflict");
        }
        assertThat(rows(id)).hasSize(5);assertThat(service.getForStudent(student,id).history().incomplete()).isFalse();
    }
    private String confirm(CountDownLatch gate,Long id)throws InterruptedException{
        gate.await();try{service.confirm(student,id);return "success";}catch(OrderConflictException e){return "conflict";}
    }

    @Test void databaseRejectsDuplicateActionAndWrongActorRole() {
        Long id=create();
        String insert="INSERT INTO order_events(order_id,action,actor_id,actor_role,actor_name,occurred_at) VALUES(?,?,?,?,?,NOW(6))";
        assertThatThrownBy(()->jdbc.update(insert,id,"SUBMITTED",student.getId(),"STUDENT","重复提交" )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update(insert,id,"ASSIGNED",student.getId(),"STUDENT","错误角色" )).isInstanceOf(DataAccessException.class);
        assertThat(rows(id)).hasSize(1);
    }
}
