package com.fixcampus.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "repair_orders")
public class RepairOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private User worker;

    @Column(nullable = false, length = 120)
    private String location;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private RepairCategory category;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private RepairStatus status;

    @Column(name = "repair_result", length = 1000)
    private String repairResult;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime completedAt;

    protected RepairOrder() { }

    public RepairOrder(User student, String location, RepairCategory category, String description, LocalDateTime createdAt) {
        this.student = student;
        this.location = location;
        this.category = category;
        this.description = description;
        this.status = RepairStatus.PENDING_ASSIGNMENT;
        this.createdAt = createdAt;
    }

    // Called only by the service after locking and checking ownership and current state.
    public void cancel() { status = RepairStatus.CANCELLED; }

    // Assignment and cancellation follow the same service-level row-lock contract.
    public void assignTo(User worker) {
        this.worker = worker;
        status = RepairStatus.PENDING_REPAIR;
    }

    // Worker transitions follow the same lock, ownership and latest-state checks in the service.
    public void startRepair() { status = RepairStatus.IN_PROGRESS; }

    public void submitRepair(String repairResult) {
        this.repairResult = repairResult;
        status = RepairStatus.PENDING_CONFIRMATION;
    }

    public void confirmCompletion(LocalDateTime confirmedAt) {
        status = RepairStatus.COMPLETED;
        completedAt = confirmedAt;
    }

    public Long getId() { return id; }
    public User getStudent() { return student; }
    public User getWorker() { return worker; }
    public String getLocation() { return location; }
    public RepairCategory getCategory() { return category; }
    public String getDescription() { return description; }
    public RepairStatus getStatus() { return status; }
    public String getRepairResult() { return repairResult; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
