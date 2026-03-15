package com.github.lystran.mochat.common.session;

/**
 * 表示把在线路由写进存储时失败了。
 */
public final class SessionRouteWriteException extends RuntimeException {
    private final boolean outcomeUnknown;

    /**
     * 用错误说明、根因和“结果是否未知”标记创建异常。
     */
    private SessionRouteWriteException(String message, Throwable cause, boolean outcomeUnknown) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    /**
     * 创建一条“确定没写成功”的异常。
     */
    public static SessionRouteWriteException definitiveFailure(String message, Throwable cause) {
        return new SessionRouteWriteException(message, cause, false);
    }

    /**
     * 创建一条“确定没写成功”的异常，不带根因。
     */
    public static SessionRouteWriteException definitiveFailure(String message) {
        return definitiveFailure(message, null);
    }

    /**
     * 创建一条“可能已经写了，但当前不敢确定”的异常。
     */
    public static SessionRouteWriteException outcomeUnknown(String message, Throwable cause) {
        return new SessionRouteWriteException(message, cause, true);
    }

    /**
     * 创建一条“可能已经写了，但当前不敢确定”的异常，不带根因。
     */
    public static SessionRouteWriteException outcomeUnknown(String message) {
        return outcomeUnknown(message, null);
    }

    /**
     * 判断这次失败后，外层需不需要再做额外核实。
     */
    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
