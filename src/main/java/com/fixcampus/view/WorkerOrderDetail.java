package com.fixcampus.view;

import java.time.LocalDateTime;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;

/** Assigned worker view: only the student's name and optional contact are needed for repair. */
public record WorkerOrderDetail(Long id, String location, RepairCategory category, String description,
                                RepairStatus status, String studentName, String studentPhone,
                                String repairResult, LocalDateTime createdAt, LocalDateTime completedAt, OrderHistory history) {
    public boolean canStart() { return status == RepairStatus.PENDING_REPAIR; }
    public boolean canComplete() { return status == RepairStatus.IN_PROGRESS; }
}
