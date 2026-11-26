package com.fixcampus.exception;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException() { super("通知不存在或无权访问"); }
}
