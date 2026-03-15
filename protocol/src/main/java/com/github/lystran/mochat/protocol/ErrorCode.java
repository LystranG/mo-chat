package com.github.lystran.mochat.protocol;

/**
 * 列出聊天协议和业务处理时会返回的错误码。
 */
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

    /**
     * 给每个错误码固定一个协议里要传的整数值。
     */
    ErrorCode(int code) {
        this.code = code;
    }

    /**
     * 取出这个错误码在协议里对应的整数值。
     */
    public int code() {
        return code;
    }
}
