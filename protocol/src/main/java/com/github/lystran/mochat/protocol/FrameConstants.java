package com.github.lystran.mochat.protocol;

/**
 * 集中定义聊天二进制包头的长度和偏移量。
 */
public final class FrameConstants {
    public static final int MAGIC_BYTES = 4;
    public static final int VERSION_BYTES = 1;
    public static final int MSG_TYPE_BYTES = 1;
    public static final int SERIALIZER_BYTES = 1;
    public static final int BODY_LENGTH_BYTES = 4;

    public static final int MAGIC_OFFSET = 0;
    public static final int VERSION_OFFSET = MAGIC_OFFSET + MAGIC_BYTES;
    public static final int MSG_TYPE_OFFSET = VERSION_OFFSET + VERSION_BYTES;
    public static final int SERIALIZER_OFFSET = MSG_TYPE_OFFSET + MSG_TYPE_BYTES;
    public static final int BODY_LENGTH_OFFSET = SERIALIZER_OFFSET + SERIALIZER_BYTES;

    public static final int HEADER_LENGTH = MAGIC_BYTES + VERSION_BYTES + MSG_TYPE_BYTES + SERIALIZER_BYTES + BODY_LENGTH_BYTES;
    public static final int PROTOCOL_VERSION = 2; // 从 1 改为 2
    public static final int DEFAULT_MAX_FRAME_LENGTH = 64 * 1024;

    /**
     * 防止被当成普通工具类实例化。
     */
    private FrameConstants() {
    }
}
