package com.example.user;

/**
 * 权限不足。classpath 里只有 spring-security-crypto，没有 security-core，
 * 所以自己定义一个，由 GlobalExceptionHandler 映射成 403。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
