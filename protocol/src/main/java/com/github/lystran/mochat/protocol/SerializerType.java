package com.github.lystran.mochat.protocol;

public enum SerializerType {
    PROTOBUF(1);

    private final int code;

    SerializerType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
