package com.github.lystran.mochat.logic.service;

/**
 * 登录或注册时，认证参数不合法就抛出这个异常。
 */
public final class AuthValidationException extends RuntimeException {
    /**
     * 用明确的错误信息告诉上层是哪一项认证参数有问题。
     */
    public AuthValidationException(String message) {
        super(message);
    }
}
