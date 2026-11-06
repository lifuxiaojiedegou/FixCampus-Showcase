package com.fixcampus.form;

import com.fixcampus.validation.CodePointLength;

public class CompleteRepairForm {
    @CodePointLength(max = 1000, message = "维修说明最多填写 1000 个字符")
    private String repairResult;

    public String getRepairResult() { return repairResult; }
    public void setRepairResult(String value) {
        repairResult = value == null || value.isBlank() ? null : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
