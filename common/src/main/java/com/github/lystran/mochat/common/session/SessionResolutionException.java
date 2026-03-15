package com.github.lystran.mochat.common.session;

/**
 * 表示查 session 过程中出错了。
 */
public final class SessionResolutionException extends RuntimeException {
    /**
     * 用错误说明创建异常。
     */
    public SessionResolutionException(String message) {
        super(message);
    }

    /**
     * 用错误说明和根因创建异常。
     */
    public SessionResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
