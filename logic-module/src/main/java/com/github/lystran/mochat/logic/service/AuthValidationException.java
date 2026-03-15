package com.github.lystran.mochat.logic.service;

/**
 * 表示登录或注册参数不满足认证约束。
 */
public final class AuthValidationException extends RuntimeException {
    /**
     * 使用认证校验失败信息构造异常。
     */
    public AuthValidationException(String message) {
        super(message);
    }
}
