package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.protocol.ErrorCode;

/**
 * 表示消息在进入正式处理前被业务规则拒绝。
 */
public final class MessageRejectException extends IllegalArgumentException {
    private final ErrorCode errorCode;

    /**
     * 创建一个带协议错误码的拒绝异常。
     */
    public MessageRejectException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 返回要发给客户端的错误码。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
