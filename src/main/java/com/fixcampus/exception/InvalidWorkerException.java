package com.fixcampus.exception;

public class InvalidWorkerException extends RuntimeException {
    public InvalidWorkerException() { super("所选维修人员不存在或不可分配，请重新选择。"); }
}
