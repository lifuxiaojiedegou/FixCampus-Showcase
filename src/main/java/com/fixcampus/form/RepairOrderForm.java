package com.fixcampus.form;

import com.fixcampus.entity.RepairCategory;
import com.fixcampus.validation.CodePointLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RepairOrderForm {
    @NotBlank(message = "请输入报修地点")
    @CodePointLength(min = 1, max = 120, message = "报修地点需为 1～120 个字符")
    private String location;

    @NotNull(message = "请选择故障类型")
    private RepairCategory category;

    @NotBlank(message = "请输入问题描述")
    @CodePointLength(min = 1, max = 2000, message = "问题描述需为 1～2000 个字符")
    private String description;

    public String getLocation() { return location; }
    public void setLocation(String value) { location = value == null ? null : value.strip(); }
    public RepairCategory getCategory() { return category; }
    public void setCategory(RepairCategory value) { category = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) {
        description = value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
