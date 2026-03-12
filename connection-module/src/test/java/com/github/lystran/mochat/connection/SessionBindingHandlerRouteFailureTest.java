package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionRouteWriteException;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionBindingHandlerRouteFailureTest {
    private static final AttributeKey<Boolean> ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE =
        AttributeKey.valueOf("mochat.routeOwnershipActive");

    @Test
    void localBindFailureDoesNotWriteRouteAndReturnsInternalError() throws Exception {
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingRouteWriter routeWriter = new RecordingRouteWriter();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(
                sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 1L)),
                new FailingRegistry(),
                routeWriter
            ),
            downstream
        );

        assertDoesNotThrow(() -> channel.writeInbound(privateMessage("session-42", 200L, 88L)));

        assertEquals(0, routeWriter.writeCount);
        assertEquals(0, downstream.messageCount);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code());
    }

    @Test
    void routeWriteFailureRollsBackLocalBindAndReturnsInternalError() throws Exception {
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingRegistry registry = new RecordingRegistry();
        InspectingFailingRouteWriter routeWriter = new InspectingFailingRouteWriter();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(
                sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 1L)),
                registry,
                routeWriter
            ),
            downstream
        );

        assertDoesNotThrow(() -> channel.writeInbound(privateMessage("session-42", 200L, 88L)));

        assertEquals(1, registry.bindCount);
        assertEquals(1, registry.unbindCount);
        assertFalse(registry.bound);
        assertEquals(Boolean.FALSE, routeWriter.ownershipStateDuringWrite);
        assertEquals(0, downstream.messageCount);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
        assertNull(channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
        assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code());
    }

    @Test
    void uncertainRouteWriteFailureDoesNotRestoreExistingBindingAndClosesChannel() throws Exception {
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingRegistry registry = new RecordingRegistry();
        registry.seed("active:41:5", 41L, 5L);
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(
                sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 7L)),
                registry,
                new UncertainFailingRouteWriter()
            ),
            downstream
        );
        channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).set("active:41:5");
        channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).set(41L);
        channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).set(5L);
        channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(101L);
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);

        assertDoesNotThrow(() -> channel.writeInbound(privateMessage("active:42:7", 200L, 88L)));

        assertEquals(1, registry.bindCount);
        assertEquals(2, registry.unbindCount);
        assertFalse(registry.bound);
        assertNull(registry.boundSessionId);
        assertEquals(0, downstream.messageCount);
        assertFalse(channel.isOpen());
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
        assertNull(channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
        assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code());
    }

    private static InboundRouterHandler.InboundMessage privateMessage(String sessionId, long conversationId, long toUid) {
        return new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId(sessionId)
                .setClientMsgId(1001L)
                .setConversationId(conversationId)
                .setToUid(toUid)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );
    }

    private static void assertErrorResponse(EmbeddedChannel channel, int expectedErrorCode) throws Exception {
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
            assertEquals(expectedErrorCode, error.getErrorCode());
        } finally {
            frame.release();
        }
        assertNull(channel.readOutbound());
    }

    private static final class FailingRegistry implements ChannelSessionRegistry<Channel> {
        @Override
        public void bind(ResolvedSession resolvedSession, Channel channelRef) {
            throw new IllegalStateException("local bind failed");
        }

        @Override
        public boolean unbind(String sessionId, long userId, Channel channelRef) {
            return false;
        }
    }

    private static final class RecordingRegistry implements ChannelSessionRegistry<Channel> {
        private int bindCount;
        private int unbindCount;
        private boolean bound;
        private String boundSessionId;

        @Override
        public void bind(ResolvedSession resolvedSession, Channel channelRef) {
            bindCount++;
            bound = true;
            boundSessionId = resolvedSession.sessionId();
        }

        @Override
        public boolean unbind(String sessionId, long userId, Channel channelRef) {
            unbindCount++;
            boolean previous = bound;
            bound = false;
            boundSessionId = null;
            return previous;
        }

        private void seed(String sessionId, long userId, long sessionVersion) {
            this.bound = true;
            this.boundSessionId = sessionId;
        }
    }

    private static final class RecordingRouteWriter implements SessionRouteWriter<Channel> {
        private int writeCount;

        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            writeCount++;
            return PersistedSessionRoute.none();
        }
    }

    private static final class FailingRouteWriter implements SessionRouteWriter<Channel> {
        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            throw new IllegalStateException("route persistence failed");
        }
    }

    private static final class InspectingFailingRouteWriter implements SessionRouteWriter<Channel> {
        private Boolean ownershipStateDuringWrite;

        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            ownershipStateDuringWrite = channelRef.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get();
            throw new IllegalStateException("route persistence failed");
        }
    }

    private static final class UncertainFailingRouteWriter implements SessionRouteWriter<Channel> {
        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            throw SessionRouteWriteException.outcomeUnknown("route persistence outcome unknown");
        }
    }

    private static final class RecordingInboundHandler extends ChannelInboundHandlerAdapter {
        private int messageCount;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messageCount++;
        }
    }
}
