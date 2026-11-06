package com.fixcampus.entity;

public enum Role {
    STUDENT("学生", "/student/orders"),
    WORKER("维修人员", "/worker/orders"),
    ADMIN("管理员", "/admin/orders");

    private final String label;
    private final String homePath;

    Role(String label, String homePath) {
        this.label = label;
        this.homePath = homePath;
    }

    public String getLabel() { return label; }
    public String getHomePath() { return homePath; }
}
