package com.github.lystran.mochat.protocol;

/**
 * 列出协议帧里允许使用的序列化方式。
 */
public enum SerializerType {
    PROTOBUF(1);

    private final int code;

    /**
     * 给每种序列化方式固定一个协议里要传的整数值。
     */
    SerializerType(int code) {
        this.code = code;
    }

    /**
     * 取出这种序列化方式在协议里对应的整数值。
     */
    public int code() {
        return code;
    }
}
