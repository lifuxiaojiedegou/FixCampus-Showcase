package com.fixcampus.view;

import java.time.LocalDateTime;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;

/** Admin view: student/worker identification, without user entities or credential fields. */
public record AdminOrderDetail(Long id, String location, RepairCategory category, String description,
                               RepairStatus status, String studentUsername, String studentName,
                               String workerUsername, String workerName, String repairResult,
                               LocalDateTime createdAt, LocalDateTime completedAt, OrderHistory history) {
    public boolean canAssign() { return status == RepairStatus.PENDING_ASSIGNMENT; }
}
