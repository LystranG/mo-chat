package com.github.lystran.mochat.protocol;

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
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_MAX_FRAME_LENGTH = 64 * 1024;

    private FrameConstants() {
    }
}
