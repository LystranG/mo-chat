package com.github.lystran.mochat.protocol;

/**
 * 定义协议里支持的消息类型。
 */
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

    /**
     * 绑定这个消息类型在协议里的编号。
     */
    MsgType(int code) {
        this.code = code;
    }

    /**
     * 返回要写进协议包头的消息类型编号。
     */
    public int code() {
        return code;
    }
}
