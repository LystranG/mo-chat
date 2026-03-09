package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBindingHandlerTest {
    @Test
    void heartbeatTimeoutCleansChannelAndSessionBinding() {
        RecordingRegistry registry = new RecordingRegistry();
        SessionResolver sessionResolver = sessionId -> "session-42".equals(sessionId) ? Optional.of(42L) : Optional.empty();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 1),
            new SessionBindingHandler(sessionResolver, registry)
        );

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));

        assertEquals("session-42", registry.sessionId);
        assertEquals(42L, registry.userId);
        assertSame(channel, registry.channel);
        assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void nonHeartbeatTrafficDoesNotRefreshHeartbeatTimeout() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler(1, 1));

        channel.advanceTimeBy(900, TimeUnit.MILLISECONDS);
        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));
        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void firstPrivateMessageWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        ));
    }

    @Test
    void invalidSessionAfterBindingClearsBindingReturnsErrorAndStopsRouting() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        SessionResolver sessionResolver = sessionId -> "session-42".equals(sessionId) ? Optional.of(42L) : Optional.empty();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionResolver, registry),
            downstream
        );

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));

        assertEquals(1, downstream.messageCount);
        assertEquals("session-42", registry.sessionId);
        assertEquals(42L, registry.userId);
        assertSame(channel, registry.channel);
        assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.readOutbound());

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(1002L)
                .setConversationId(201L)
                .setToUid(89L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        ));

        assertEquals(1, downstream.messageCount);
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertSessionInvalidResponse(channel);
        assertNull(channel.readInbound());
    }

    @Test
    void firstGroupMessageWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.GROUP_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.GroupMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(2002L)
                .setConversationId(300L)
                .setGroupId(300L)
                .setText("hello-group")
                .build()
                .toByteArray()
        ));
    }

    @Test
    void firstReceiptWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_RECEIVE_ACK,
            SerializerType.PROTOBUF,
            Mochat.ClientReceiveAck.newBuilder()
                .setSessionId("invalid-session")
                .setConversationId(200L)
                .setLatestReceivedSeq(66L)
                .build()
                .toByteArray()
        ));
    }

    private static void assertFirstInvalidSessionReturnsAuthFailure(InboundRouterHandler.InboundMessage inboundMessage) throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionId -> Optional.empty(), registry),
            downstream
        );

        channel.writeInbound(inboundMessage);

        assertEquals(0, downstream.messageCount);
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertSessionInvalidResponse(channel);
        assertNull(channel.readInbound());
    }

    private static void assertSessionInvalidResponse(EmbeddedChannel channel) throws Exception {
        ByteBuf frame = channel.readOutbound();
        assertNotNull(frame);
        try {
            assertEquals(FrameConstants.HEADER_LENGTH + frame.getInt(FrameConstants.BODY_LENGTH_OFFSET), frame.readableBytes());
            assertEquals(0x4D4F4348, frame.readInt());
            assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
            assertEquals(MsgType.ERROR_RESPONSE.code(), frame.readUnsignedByte());
            assertEquals(SerializerType.PROTOBUF.code(), frame.readUnsignedByte());
            int bodyLength = frame.readInt();
            byte[] body = new byte[bodyLength];
            frame.readBytes(body);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(body);
            assertEquals(1000, error.getErrorCode());
        } finally {
            frame.release();
        }
        assertNull(channel.readOutbound());
    }

    private static final class RecordingInboundHandler extends ChannelInboundHandlerAdapter {
        private int messageCount;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messageCount++;
        }
    }

    private static final class RecordingRegistry implements ChannelSessionRegistry<Channel> {
        private String sessionId;
        private long userId;
        private Channel channel;
        private boolean empty = true;

        @Override
        public void bind(String sessionId, long userId, Channel channelRef) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.channel = channelRef;
            this.empty = false;
        }

        @Override
        public boolean unbind(String sessionId, long userId, Channel channelRef) {
            if (!this.empty && this.userId == userId && this.channel == channelRef && this.sessionId.equals(sessionId)) {
                this.sessionId = null;
                this.userId = 0L;
                this.channel = null;
                this.empty = true;
                return true;
            }
            return false;
        }
    }
}
