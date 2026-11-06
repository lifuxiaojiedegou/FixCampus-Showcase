package com.fixcampus.entity;

public enum RepairStatus {
    PENDING_ASSIGNMENT("待分配", "pending"), PENDING_REPAIR("待维修", "assigned"),
    IN_PROGRESS("维修中", "active"), PENDING_CONFIRMATION("待确认", "confirmation"),
    COMPLETED("已完成", "completed"), CANCELLED("已取消", "cancelled");

    private final String label;
    private final String cssClass;
    RepairStatus(String label, String cssClass) { this.label = label; this.cssClass = cssClass; }
    public String getLabel() { return label; }
    public String getCssClass() { return cssClass; }
}
