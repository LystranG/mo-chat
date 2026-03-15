package com.github.lystran.mochat.protocol;

/**
 * 列出聊天二进制协议里支持的消息类型。
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
     * 给每种消息类型固定一个协议里要传的整数值。
     */
    MsgType(int code) {
        this.code = code;
    }

    /**
     * 取出这个消息类型在协议里对应的整数值。
     */
    public int code() {
        return code;
    }
}
