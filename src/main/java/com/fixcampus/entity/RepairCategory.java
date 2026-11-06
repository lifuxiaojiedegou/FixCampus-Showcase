package com.fixcampus.entity;

public enum RepairCategory {
    WATER_ELECTRICITY("水电设施"), DOORS_FURNITURE("门窗家具"), NETWORK("网络"),
    AIR_CONDITIONING("空调"), OTHER("其他");

    private final String label;
    RepairCategory(String label) { this.label = label; }
    public String getLabel() { return label; }
}
