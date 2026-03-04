package com.github.lystran.mochat.protocol;

import com.github.lystran.mochat.protocol.proto.Mochat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtoRoundTripTest {
    @Test
    void sendAckRoundTrips() throws Exception {
        var ack = Mochat.SendAck.newBuilder()
                .setClientMsgId(1L)
                .setMsgId(2L)
                .setSeq(3L)
                .setServerTimeMs(4L)
                .build();

        byte[] bytes = ack.toByteArray();
        var parsed = Mochat.SendAck.parseFrom(bytes);
        assertEquals(ack, parsed);
    }

    @Test
    void protocolEnumsMatchDesign() {
        assertEquals(1, MsgType.CLIENT_HEARTBEAT.code());
        assertEquals(2, MsgType.SERVER_HEARTBEAT.code());
        assertEquals(3, MsgType.PRIVATE_MESSAGE.code());
        assertEquals(4, MsgType.GROUP_MESSAGE.code());
        assertEquals(5, MsgType.SEND_ACK.code());
        assertEquals(6, MsgType.ERROR_RESPONSE.code());
        assertEquals(7, MsgType.CLIENT_RECEIVE_ACK.code());
        assertEquals(8, MsgType.DELIVERED_ACK.code());

        assertEquals(1, SerializerType.PROTOBUF.code());

        assertEquals(1000, ErrorCode.SESSION_INVALID.code());
        assertEquals(1001, ErrorCode.SESSION_EXPIRED.code());
        assertEquals(1100, ErrorCode.RATE_LIMITED.code());
        assertEquals(1200, ErrorCode.INVALID_FRAME.code());
        assertEquals(1201, ErrorCode.INVALID_BODY.code());
        assertEquals(1202, ErrorCode.UNSUPPORTED_VERSION.code());
        assertEquals(1203, ErrorCode.UNSUPPORTED_SERIALIZER.code());
        assertEquals(1300, ErrorCode.NOT_FRIEND.code());
        assertEquals(1301, ErrorCode.FRIEND_BLOCKED.code());
        assertEquals(1400, ErrorCode.NOT_IN_GROUP.code());
        assertEquals(1500, ErrorCode.MQ_PUBLISH_FAILED.code());
        assertEquals(1501, ErrorCode.INTERNAL_ERROR.code());
    }

    @Test
    void frameConstantsMatchProtocol() {
        assertEquals(4, FrameConstants.MAGIC_BYTES);
        assertEquals(1, FrameConstants.VERSION_BYTES);
        assertEquals(1, FrameConstants.MSG_TYPE_BYTES);
        assertEquals(1, FrameConstants.SERIALIZER_BYTES);
        assertEquals(4, FrameConstants.BODY_LENGTH_BYTES);
        assertEquals(11, FrameConstants.HEADER_LENGTH);
        assertEquals(1, FrameConstants.PROTOCOL_VERSION);
        assertEquals(64 * 1024, FrameConstants.DEFAULT_MAX_FRAME_LENGTH);
    }
}
