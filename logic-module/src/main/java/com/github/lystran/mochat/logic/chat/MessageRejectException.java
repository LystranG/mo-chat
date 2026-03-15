package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.protocol.ErrorCode;

/**
 * 表示消息被业务规则拒绝，并携带可直接回传客户端的协议错误码。
 */
public final class MessageRejectException extends IllegalArgumentException {
    private final ErrorCode errorCode;

    /**
     * 使用协议错误码和解释信息构造拒绝异常。
     */
    public MessageRejectException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 返回应映射到协议层的业务错误码。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
