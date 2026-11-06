package com.fixcampus.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class AssignWorkerForm {
    @NotNull(message = "请选择维修人员")
    @Positive(message = "请选择有效的维修人员")
    private Long workerId;

    public Long getWorkerId() { return workerId; }
    public void setWorkerId(Long workerId) { this.workerId = workerId; }
}
