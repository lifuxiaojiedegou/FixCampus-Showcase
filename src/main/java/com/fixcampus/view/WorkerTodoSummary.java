package com.fixcampus.view;

public record WorkerTodoSummary(long pendingRepair, long inProgress) {
    public long total() { return pendingRepair + inProgress; }
}
