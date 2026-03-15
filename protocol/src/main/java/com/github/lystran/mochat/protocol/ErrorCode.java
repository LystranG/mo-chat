package com.github.lystran.mochat.protocol;

/**
 * 定义连接层和业务层统一使用的错误码。
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
     * 绑定这个错误对应的数字编码。
     */
    ErrorCode(int code) {
        this.code = code;
    }

    /**
     * 返回要写进协议里的数字错误码。
     */
    public int code() {
        return code;
    }
}
