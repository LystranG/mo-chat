package com.github.lystran.mochat.protocol;

public enum MsgType {
    CLIENT_HEARTBEAT(1),
    SERVER_HEARTBEAT(2),
    PRIVATE_MESSAGE(3),
    GROUP_MESSAGE(4),
    SEND_ACK(5),
    ERROR_RESPONSE(6),
    CLIENT_RECEIVE_ACK(7),
    DELIVERED_ACK(8);

    private final int code;

    MsgType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
