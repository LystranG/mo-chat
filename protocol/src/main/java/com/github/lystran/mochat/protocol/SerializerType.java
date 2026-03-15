package com.github.lystran.mochat.protocol;

/**
 * 定义协议正文使用的序列化方式。
 */
public enum SerializerType {
    PROTOBUF(1);

    private final int code;

    /**
     * 绑定这个序列化方式在协议里的编号。
     */
    SerializerType(int code) {
        this.code = code;
    }

    /**
     * 返回要写进协议包头的序列化方式编号。
     */
    public int code() {
        return code;
    }
}
