package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.OrderEvent;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.exception.OrderNotFoundException;
import com.fixcampus.exception.InvalidWorkerException;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.view.OrderDetail;
import com.fixcampus.view.OrderSummary;
import com.fixcampus.view.AdminOrderDetail;
import com.fixcampus.view.WorkerOrderDetail;
import com.fixcampus.view.WorkerTodoSummary;
import com.fixcampus.view.OrderHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RepairOrderService {
    private final RepairOrderRepository orders;
    private final UserRepository users;
    private final Clock clock;
    private final OrderEventRepository events;

    public RepairOrderService(RepairOrderRepository orders, UserRepository users, Clock clock, OrderEventRepository events) {
        this.orders = orders;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public Long createForStudent(CampusPrincipal actor, @NotNull @Valid RepairOrderForm form) {
        requireStudent(actor);
        var student = users.findByIdAndRole(actor.getId(), Role.STUDENT)
                .orElseThrow(() -> new AccessDeniedException("学生账号不可用"));
        var order = new RepairOrder(student, form.getLocation(), form.getCategory(), form.getDescription(),
                LocalDateTime.now(clock));
        orders.saveAndFlush(order);
        record(order, actor, OrderAction.SUBMITTED, order.getCreatedAt(), null);
        return order.getId();
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listForStudent(CampusPrincipal actor) {
        requireStudent(actor);
        return orders.findForStudent(actor.getId()).stream().map(order -> new OrderSummary(order.getId(),
                order.getLocation(), order.getCategory(), order.getStatus(), order.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> searchForStudent(CampusPrincipal actor, @NotNull @Valid OrderFilterForm filter) {
        requireStudent(actor);
        return summaries(orders.searchForStudent(actor.getId(), filter.statusValue(), filter.orderId(), filter.locationPattern()));
    }

    @Transactional(readOnly = true)
    public long countAwaitingConfirmation(CampusPrincipal actor) {
        requireStudent(actor);
        return orders.countByStudent_IdAndStatus(actor.getId(), RepairStatus.PENDING_CONFIRMATION);
    }

    @Transactional(readOnly = true)
    public OrderDetail getForStudent(CampusPrincipal actor, Long id) {
        requireStudent(actor);
        requireOrderId(id);
        var order = orders.findForStudentById(id, actor.getId()).orElseThrow(OrderNotFoundException::new);
        return new OrderDetail(order.getId(), order.getLocation(), order.getCategory(), order.getDescription(),
                order.getStatus(), order.getWorker() == null ? null : order.getWorker().getRealName(),
                order.getRepairResult(), order.getCreatedAt(), order.getCompletedAt(), history(order));
    }

    @Transactional
    public void cancel(CampusPrincipal actor, Long id) {
        requireStudent(actor);
        requireOrderId(id);
        var order = orders.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        if (!actor.getId().equals(order.getStudent().getId())) { throw new OrderNotFoundException(); }
        if (order.getStatus() != RepairStatus.PENDING_ASSIGNMENT) { throw new OrderConflictException(); }
        order.cancel();
        record(order, actor, OrderAction.CANCELLED, LocalDateTime.now(clock), null);
        // Commit flushes inside this transaction; a failed commit never returns a success result.
    }

    @Transactional
    public void confirm(CampusPrincipal actor, Long id) {
        requireStudent(actor);
        requireOrderId(id);
        var order = orders.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        if (!actor.getId().equals(order.getStudent().getId())) { throw new OrderNotFoundException(); }
        if (order.getStatus() != RepairStatus.PENDING_CONFIRMATION) { throw new OrderConflictException(); }
        var time = LocalDateTime.now(clock);
        order.confirmCompletion(time);
        record(order, actor, OrderAction.CONFIRMED, time, null);
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listForAdmin(CampusPrincipal actor, RepairStatus status) {
        requireAdmin(actor);
        return orders.findAllOrStatus(status).stream().map(order -> new OrderSummary(order.getId(),
                order.getLocation(), order.getCategory(), order.getStatus(), order.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> searchForAdmin(CampusPrincipal actor, @NotNull @Valid OrderFilterForm filter) {
        requireAdmin(actor);
        return summaries(orders.searchForAdmin(filter.statusValue(), filter.orderId(), filter.locationPattern()));
    }

    @Transactional(readOnly = true)
    public long countAwaitingAssignment(CampusPrincipal actor) {
        requireAdmin(actor);
        return orders.countByStatus(RepairStatus.PENDING_ASSIGNMENT);
    }

    @Transactional(readOnly = true)
    public AdminOrderDetail getForAdmin(CampusPrincipal actor, Long id) {
        requireAdmin(actor);
        requireOrderId(id);
        var order = orders.findForAdminById(id).orElseThrow(OrderNotFoundException::new);
        return new AdminOrderDetail(order.getId(), order.getLocation(), order.getCategory(), order.getDescription(),
                order.getStatus(), order.getStudent().getUsername(), order.getStudent().getRealName(),
                order.getWorker() == null ? null : order.getWorker().getUsername(),
                order.getWorker() == null ? null : order.getWorker().getRealName(), order.getRepairResult(),
                order.getCreatedAt(), order.getCompletedAt(), history(order));
    }

    @Transactional
    public void assign(CampusPrincipal actor, Long id, @NotNull @Positive Long workerId) {
        requireAdmin(actor);
        requireOrderId(id);
        var order = orders.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        if (order.getStatus() != RepairStatus.PENDING_ASSIGNMENT) { throw new OrderConflictException(); }
        var worker = users.findByIdAndRole(workerId, Role.WORKER).orElseThrow(InvalidWorkerException::new);
        order.assignTo(worker);
        record(order, actor, OrderAction.ASSIGNED, LocalDateTime.now(clock), "分配给维修人员：" + worker.getRealName());
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listForWorker(CampusPrincipal actor) {
        requireWorker(actor);
        return orders.findForWorker(actor.getId()).stream().map(order -> new OrderSummary(order.getId(),
                order.getLocation(), order.getCategory(), order.getStatus(), order.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> searchForWorker(CampusPrincipal actor, @NotNull @Valid OrderFilterForm filter) {
        requireWorker(actor);
        return summaries(orders.searchForWorker(actor.getId(), filter.statusValue(), filter.orderId(), filter.locationPattern()));
    }

    @Transactional(readOnly = true)
    public WorkerTodoSummary workerTodo(CampusPrincipal actor) {
        requireWorker(actor);
        return new WorkerTodoSummary(orders.countByWorker_IdAndStatus(actor.getId(), RepairStatus.PENDING_REPAIR),
                orders.countByWorker_IdAndStatus(actor.getId(), RepairStatus.IN_PROGRESS));
    }

    private static List<OrderSummary> summaries(List<RepairOrder> matches) {
        return matches.stream().map(order -> new OrderSummary(order.getId(), order.getLocation(), order.getCategory(),
                order.getStatus(), order.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public WorkerOrderDetail getForWorker(CampusPrincipal actor, Long id) {
        requireWorker(actor);
        requireOrderId(id);
        var order = orders.findForWorkerById(id, actor.getId()).orElseThrow(OrderNotFoundException::new);
        return new WorkerOrderDetail(order.getId(), order.getLocation(), order.getCategory(), order.getDescription(),
                order.getStatus(), order.getStudent().getRealName(), order.getStudent().getPhone(),
                order.getRepairResult(), order.getCreatedAt(), order.getCompletedAt(), history(order));
    }

    @Transactional
    public void start(CampusPrincipal actor, Long id) {
        var order = lockWorkerOrder(actor, id);
        if (order.getStatus() != RepairStatus.PENDING_REPAIR) { throw new OrderConflictException(); }
        order.startRepair();
        record(order, actor, OrderAction.REPAIR_STARTED, LocalDateTime.now(clock), null);
    }

    @Transactional
    public void complete(CampusPrincipal actor, Long id, @NotNull @Valid CompleteRepairForm form) {
        var order = lockWorkerOrder(actor, id);
        if (order.getStatus() != RepairStatus.IN_PROGRESS) { throw new OrderConflictException(); }
        order.submitRepair(form.getRepairResult());
        record(order, actor, OrderAction.REPAIR_COMPLETED, LocalDateTime.now(clock), form.getRepairResult());
        // completedAt belongs exclusively to the student's later confirmation, not this submission.
    }

    private RepairOrder lockWorkerOrder(CampusPrincipal actor, Long id) {
        requireWorker(actor);
        requireOrderId(id);
        var order = orders.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        if (order.getWorker() == null || !actor.getId().equals(order.getWorker().getId())) {
            throw new OrderNotFoundException();
        }
        return order;
    }

    private static void requireWorker(CampusPrincipal actor) {
        if (actor == null || actor.getId() == null || actor.getRole() != Role.WORKER) {
            throw new AccessDeniedException("此操作仅限维修人员账号");
        }
    }

    private void record(RepairOrder order, CampusPrincipal actor, OrderAction action, LocalDateTime time, String detail) {
        events.saveAndFlush(new OrderEvent(order, action, users.getReferenceById(actor.getId()),
                actor.getRole(), actor.getRealName(), time, detail));
    }

    private OrderHistory history(RepairOrder order) {
        return OrderHistory.from(order, events.findByOrder_IdOrderByIdAsc(order.getId()));
    }

    private static void requireAdmin(CampusPrincipal actor) {
        if (actor == null || actor.getId() == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("此操作仅限管理员账号");
        }
    }

    private static void requireStudent(CampusPrincipal actor) {
        if (actor == null || actor.getId() == null || actor.getRole() != Role.STUDENT) {
            throw new AccessDeniedException("此操作仅限学生账号");
        }
    }

    private static void requireOrderId(Long id) {
        if (id == null || id <= 0) { throw new OrderNotFoundException(); }
    }
}
