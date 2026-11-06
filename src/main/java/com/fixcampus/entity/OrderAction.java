package com.fixcampus.entity;

import java.util.List;

public enum OrderAction {
    SUBMITTED("提交报修"), ASSIGNED("分配维修人员"), REPAIR_STARTED("开始维修"),
    REPAIR_COMPLETED("提交维修结果"), CONFIRMED("确认维修完成"), CANCELLED("撤销工单");
    private final String label;
    OrderAction(String label) { this.label = label; }
    public String getLabel() { return label; }

    public static List<OrderAction> expectedFor(RepairStatus status) {
        return switch (status) {
            case PENDING_ASSIGNMENT -> List.of(SUBMITTED);
            case PENDING_REPAIR -> List.of(SUBMITTED, ASSIGNED);
            case IN_PROGRESS -> List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED);
            case PENDING_CONFIRMATION -> List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED, REPAIR_COMPLETED);
            case COMPLETED -> List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED, REPAIR_COMPLETED, CONFIRMED);
            case CANCELLED -> List.of(SUBMITTED, CANCELLED);
        };
    }
}
