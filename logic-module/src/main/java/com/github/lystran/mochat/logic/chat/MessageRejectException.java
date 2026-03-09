package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.protocol.ErrorCode;

public final class MessageRejectException extends IllegalArgumentException {
    private final ErrorCode errorCode;

    public MessageRejectException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
