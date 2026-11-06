package com.fixcampus.exception;

public class OrderConflictException extends RuntimeException {
    public OrderConflictException() { super("工单状态已变化，请刷新后重试。"); }
}
