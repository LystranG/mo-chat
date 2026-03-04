package com.github.lystran.mochat.protocol;

public enum ErrorCode {
    SESSION_INVALID(1000),
    SESSION_EXPIRED(1001),
    RATE_LIMITED(1100),
    INVALID_FRAME(1200),
    INVALID_BODY(1201),
    UNSUPPORTED_VERSION(1202),
    UNSUPPORTED_SERIALIZER(1203),
    NOT_FRIEND(1300),
    FRIEND_BLOCKED(1301),
    NOT_IN_GROUP(1400),
    MQ_PUBLISH_FAILED(1500),
    INTERNAL_ERROR(1501);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
