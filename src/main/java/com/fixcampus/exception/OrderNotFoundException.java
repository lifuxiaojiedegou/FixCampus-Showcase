package com.fixcampus.exception;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() { super("工单不存在或无权访问"); }
}
