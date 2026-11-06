package com.fixcampus.form;

import com.fixcampus.entity.RepairStatus;
import com.fixcampus.validation.CodePointLength;
import jakarta.validation.constraints.AssertTrue;

/** Read-only list filters; ownership always comes from the authenticated principal. */
public class OrderFilterForm {
    private String status = "";
    @CodePointLength(max = 120, message = "搜索内容最多 120 个字符")
    private String q = "";

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value == null ? "" : value; }
    public String getQ() { return q; }
    public void setQ(String value) { q = value == null ? "" : value.strip(); }

    @AssertTrue(message = "请选择有效的工单状态。")
    public boolean isStatusValid() {
        if (status.isEmpty()) return true;
        try { RepairStatus.valueOf(status); return true; }
        catch (IllegalArgumentException exception) { return false; }
    }

    public RepairStatus statusValue() { return status.isEmpty() ? null : RepairStatus.valueOf(status); }
    public boolean hasFilters() { return !status.isEmpty() || !q.isEmpty(); }

    public Long orderId() {
        String number = q.startsWith("#") ? q.substring(1) : q;
        if (!number.matches("[0-9]+")) return null;
        try { long id = Long.parseLong(number); return id > 0 ? id : null; }
        catch (NumberFormatException exception) { return null; }
    }

    public String locationPattern() {
        if (q.isEmpty()) return null;
        // Treat LIKE metacharacters as literal input, never as a match-all shortcut.
        return "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
