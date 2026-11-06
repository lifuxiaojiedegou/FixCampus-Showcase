package com.fixcampus.exception;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException() { super("该用户名已被使用，请换一个用户名"); }
}
