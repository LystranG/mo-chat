package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatChannelInitializerTest {
    private static final int MAGIC = 0x4D4F4348;

    @Test
    void oversizedFrameIsRejected() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 32));

        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, new byte[64])));
    }

    @Test
    void installsExplicitProtocolCodecHandler() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertNotNull(channel.pipeline().get("protocolCodec"));
    }

    @Test
    void installsTlsHandlerBeforeFrameDecoderWhenSslContextIsProvided() throws Exception {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), new StubSslContext(), 256));

        assertNotNull(channel.pipeline().get("tls"));
        assertTrue(channel.pipeline().names().indexOf("tls") < channel.pipeline().names().indexOf("frameDecoder"));
    }

    @Test
    void parsesMsgTypeAndPublishesInboundEvent() throws Exception {
        var eventBus = new InProcessEventBus();
        var events = new CopyOnWriteArrayList<String>();
        try (var ignored = eventBus.subscribe(InboundRouterHandler.DEFAULT_INBOUND_TOPIC, events::add)) {
            var channel = new EmbeddedChannel(new ChatChannelInitializer(eventBus, null, 256));

            channel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, new byte[]{1, 2, 3}));
            assertEquals(1, events.size());
            assertEquals("PRIVATE_MESSAGE|PROTOBUF|AQID", events.get(0));
        }
    }

    @Test
    void rejectsInvalidProtocolMagic() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertThrows(DecoderException.class, () -> channel.writeInbound(buildFrame(0x01020304, MsgType.PRIVATE_MESSAGE, new byte[]{1})));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertThrows(
            DecoderException.class,
            () -> channel.writeInbound(buildFrame(MAGIC, FrameConstants.PROTOCOL_VERSION + 1, MsgType.PRIVATE_MESSAGE.code(), SerializerType.PROTOBUF.code(), new byte[]{1}))
        );
    }

    @Test
    void rejectsUnknownSerializerCode() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertThrows(
            DecoderException.class,
            () -> channel.writeInbound(buildFrame(MAGIC, FrameConstants.PROTOCOL_VERSION, MsgType.PRIVATE_MESSAGE.code(), 99, new byte[]{1}))
        );
    }

    @Test
    void rejectsUnknownMsgTypeCode() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertThrows(
            DecoderException.class,
            () -> channel.writeInbound(buildFrame(MAGIC, FrameConstants.PROTOCOL_VERSION, 99, SerializerType.PROTOBUF.code(), new byte[]{1}))
        );
    }

    @Test
    void heartbeatFramesAreNotPublishedAsInboundEvents() throws Exception {
        var eventBus = new InProcessEventBus();
        var events = new CopyOnWriteArrayList<String>();
        try (var ignored = eventBus.subscribe(InboundRouterHandler.DEFAULT_INBOUND_TOPIC, events::add)) {
            var channel = new EmbeddedChannel(new ChatChannelInitializer(eventBus, null, 256, 1));

            channel.writeInbound(buildFrame(MsgType.CLIENT_HEARTBEAT, new byte[0]));

            assertEquals(0, events.size());
        }
    }

    @Test
    void closesIdleConnectionWhenHeartbeatsStop() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256, 1));

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void heartbeatTimeoutAlsoUnbindsOnlineUserChannel() {
        InMemoryDirectory directory = new InMemoryDirectory();
        var channel = new EmbeddedChannel(
            new ChatChannelInitializer(
                new InProcessEventBus(),
                null,
                new StubSessionResolver(Map.of("session-1", 42L)),
                directory,
                256,
                5,
                1
            )
        );

        channel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, privateMessageBody("session-1", 200L, 88L)));
        assertTrue(directory.find(42L).isPresent());

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertTrue(directory.find(42L).isEmpty());
    }

    @Test
    void reusesSingleInitializerAcrossChannelsWhenSessionBindingIsEnabled() {
        InMemoryDirectory directory = new InMemoryDirectory();
        var initializer = new ChatChannelInitializer(
            new InProcessEventBus(),
            null,
            new StubSessionResolver(Map.of("session-1", 42L, "session-2", 43L)),
            directory,
            256,
            5,
            1
        );

        var firstChannel = new EmbeddedChannel(initializer);
        var secondChannel = new EmbeddedChannel(initializer);

        firstChannel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, privateMessageBody("session-1", 200L, 88L)));
        secondChannel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, privateMessageBody("session-2", 201L, 89L)));

        assertNotNull(firstChannel.pipeline().get("sessionBinding"));
        assertNotNull(secondChannel.pipeline().get("sessionBinding"));
        assertSame(firstChannel, directory.find(42L).orElseThrow());
        assertSame(secondChannel, directory.find(43L).orElseThrow());
    }

    @Test
    void emitsServerHeartbeatFramesOnSchedule() throws Exception {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256, 1, 5));

        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        ByteBuf frame = channel.readOutbound();
        assertTrue(frame.isReadable());
        assertEquals(MAGIC, frame.readInt());
        assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
        assertEquals(MsgType.SERVER_HEARTBEAT.code(), frame.readUnsignedByte());
        assertEquals(SerializerType.PROTOBUF.code(), frame.readUnsignedByte());

        int bodyLength = frame.readInt();
        byte[] body = new byte[bodyLength];
        frame.readBytes(body);
        Mochat.Heartbeat heartbeat = Mochat.Heartbeat.parseFrom(body);
        assertTrue(heartbeat.getServerTimeMs() > 0L);
        frame.release();
    }

    private static ByteBuf buildFrame(MsgType msgType, byte[] body) {
        return buildFrame(MAGIC, msgType, body);
    }

    private static ByteBuf buildFrame(int magic, MsgType msgType, byte[] body) {
        return buildFrame(magic, FrameConstants.PROTOCOL_VERSION, msgType.code(), SerializerType.PROTOBUF.code(), body);
    }

    private static ByteBuf buildFrame(int magic, int protocolVersion, int msgTypeCode, int serializerCode, byte[] body) {
        return Unpooled.buffer(FrameConstants.HEADER_LENGTH + body.length)
            .writeInt(magic)
            .writeByte(protocolVersion)
            .writeByte(msgTypeCode)
            .writeByte(serializerCode)
            .writeInt(body.length)
            .writeBytes(body);
    }

    private static byte[] privateMessageBody(String sessionId, long conversationId, long toUid) {
        return Mochat.PrivateMessageReq.newBuilder()
            .setSessionId(sessionId)
            .setClientMsgId(1001L)
            .setConversationId(conversationId)
            .setToUid(toUid)
            .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
            .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
            .build()
            .toByteArray();
    }

    private static final class StubSessionResolver implements SessionResolver {
        private final Map<String, Long> userIdsBySessionId;

        private StubSessionResolver(Map<String, Long> userIdsBySessionId) {
            this.userIdsBySessionId = userIdsBySessionId;
        }

        @Override
        public Optional<Long> resolveUserId(String sessionId) {
            return Optional.ofNullable(userIdsBySessionId.get(sessionId));
        }
    }

    private static final class InMemoryDirectory implements UserChannelDirectory<Channel> {
        private final Map<Long, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public void bind(long userId, Channel channelRef) {
            channels.put(userId, channelRef);
        }

        @Override
        public Optional<Channel> find(long userId) {
            return Optional.ofNullable(channels.get(userId));
        }

        @Override
        public boolean unbind(long userId, Channel channelRef) {
            return channels.remove(userId, channelRef);
        }
    }

    private static final class StubSslContext extends SslContext {
        private final SSLContext delegate;

        private StubSslContext() throws Exception {
            this.delegate = SSLContext.getDefault();
        }

        
        public boolean isClient() {
            return false;
        }

        
        public List<String> cipherSuites() {
            return List.of();
        }

        
        public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
            return List::of;
        }

        
        public SSLEngine newEngine(io.netty.buffer.ByteBufAllocator byteBufAllocator) {
            SSLEngine sslEngine = delegate.createSSLEngine();
            sslEngine.setUseClientMode(false);
            return sslEngine;
        }

        
        public SSLEngine newEngine(io.netty.buffer.ByteBufAllocator byteBufAllocator, String peerHost, int peerPort) {
            SSLEngine sslEngine = delegate.createSSLEngine(peerHost, peerPort);
            sslEngine.setUseClientMode(false);
            return sslEngine;
        }

        
        public SSLSessionContext sessionContext() {
            return delegate.getServerSessionContext();
        }
    }
}
