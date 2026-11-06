package com.fixcampus.view;

import java.time.LocalDateTime;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;

/** Student view: no user entities, password hashes or worker contact information. */
public record OrderDetail(Long id, String location, RepairCategory category, String description,
                          RepairStatus status, String workerName, String repairResult,
                          LocalDateTime createdAt, LocalDateTime completedAt, OrderHistory history) {
    public boolean canCancel() { return status == RepairStatus.PENDING_ASSIGNMENT; }
    public boolean canConfirm() { return status == RepairStatus.PENDING_CONFIRMATION; }
}
