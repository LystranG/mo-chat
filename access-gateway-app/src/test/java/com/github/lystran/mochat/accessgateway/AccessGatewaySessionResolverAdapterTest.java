package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolutionException;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.connection.ChatChannelInitializer;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import com.github.lystran.mochat.protocol.internal.common.v1.SessionPrincipal;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewaySessionResolverAdapterTest {
    private static final int MAGIC = 0x4D4F4348;

    @Test
    void resolveAuthorityPreservesGrpcSessionFenceMetadata() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-session-resolver",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", false
        ))) {
            SessionResolver sessionResolver = context.getBean(SessionResolver.class);

            SessionAuthority active = sessionResolver.resolveAuthority("active:42:7");
            SessionAuthority expired = sessionResolver.resolveAuthority("expired:42:7");
            SessionAuthority replaced = sessionResolver.resolveAuthority("replaced:42:7");
            SessionAuthority invalid = sessionResolver.resolveAuthority("invalid");

            assertEquals(SessionAuthorityStatus.ACTIVE, active.status());
            assertEquals("active:42:7", active.sessionId());
            assertEquals(42L, active.userId());
            assertEquals(7L, active.sessionVersion());

            assertEquals(SessionAuthorityStatus.EXPIRED, expired.status());
            assertEquals("expired:42:7", expired.sessionId());
            assertEquals(42L, expired.userId());
            assertEquals(7L, expired.sessionVersion());

            assertEquals(SessionAuthorityStatus.REPLACED, replaced.status());
            assertEquals("replaced:42:7", replaced.sessionId());
            assertEquals(42L, replaced.userId());
            assertEquals(7L, replaced.sessionVersion());

            assertEquals(SessionAuthorityStatus.INVALID, invalid.status());
            assertEquals("invalid", invalid.sessionId());
            assertEquals(0L, invalid.userId());
            assertEquals(0L, invalid.sessionVersion());
        }
    }

    @Test
    void resolvesActiveSessionViaGrpcAndBindsInboundConnection() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-session-resolver",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", false
        ))) {
            SessionResolver sessionResolver = context.getBean(SessionResolver.class);

            assertEquals(Optional.of(42L), sessionResolver.resolveUserId("active:42:7"));
            assertTrue(sessionResolver.resolveUserId("expired:42:7").isEmpty());
            assertTrue(sessionResolver.resolveUserId("replaced:42:7").isEmpty());
            assertTrue(sessionResolver.resolveUserId("invalid").isEmpty());

            InMemoryDirectory directory = new InMemoryDirectory();
            EmbeddedChannel activeChannel = new EmbeddedChannel(
                new ChatChannelInitializer(new InProcessEventBus(), null, sessionResolver, directory, 256, 5, 1)
            );

            activeChannel.writeInbound(buildFrame("active:42:7"));
            assertTrue(directory.find(42L).isPresent());

            EmbeddedChannel expiredChannel = new EmbeddedChannel(
                new ChatChannelInitializer(new InProcessEventBus(), null, sessionResolver, directory, 256, 5, 1)
            );
            expiredChannel.writeInbound(buildFrame("expired:42:7"));

            assertFalse(expiredChannel.hasAttr(SessionBindingHandler.USER_ID_ATTRIBUTE)
                && expiredChannel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get() != null);
            ByteBuf frame = expiredChannel.readOutbound();
            assertNotNull(frame);
            try {
                assertEquals(MAGIC, frame.readInt());
                assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
                assertEquals(MsgType.ERROR_RESPONSE.code(), frame.readUnsignedByte());
            } finally {
                frame.release();
            }
        }
    }

    @Test
    void grpcFailureSurfacesAsSessionResolutionException() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-session-resolver",
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "mochat.access-gateway.tcp.enabled", false
        ))) {
            SessionResolver sessionResolver = context.getBean(SessionResolver.class);

            assertThrows(SessionResolutionException.class, () -> sessionResolver.resolveUserId("error:42:7"));
        }
    }

    private static ByteBuf buildFrame(String sessionId) {
        byte[] body = Mochat.PrivateMessageReq.newBuilder()
            .setSessionId(sessionId)
            .setClientMsgId(1001L)
            .setConversationId(200L)
            .setToUid(88L)
            .setNonce(ByteString.copyFrom(new byte[12]))
            .setCiphertext(ByteString.copyFromUtf8("ciphertext"))
            .build()
            .toByteArray();
        return Unpooled.buffer(FrameConstants.HEADER_LENGTH + body.length)
            .writeInt(MAGIC)
            .writeByte(FrameConstants.PROTOCOL_VERSION)
            .writeByte(MsgType.PRIVATE_MESSAGE.code())
            .writeByte(SerializerType.PROTOBUF.code())
            .writeInt(body.length)
            .writeBytes(body);
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-session-resolver")
    static final class TestGrpcFactory {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        OfflineQueue offlineQueue() {
            return new OfflineQueue() {
                @Override
                public void enqueue(long userId, String payload, int maxQueueSize) {
                }

                @Override
                public java.util.List<String> drain(long userId, int maxItems) {
                    return java.util.List.of();
                }
            };
        }

        @Singleton
        SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return SessionAuthorityApiGrpc.newBlockingStub(channel);
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "access-gateway-session-resolver")
    static final class TestSessionAuthorityService extends SessionAuthorityApiGrpc.SessionAuthorityApiImplBase {
        @Override
        public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> responseObserver) {
            String sessionId = request.getSessionId();
            ResolveSessionResponse response = switch (sessionId) {
                case "active:42:7" -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE)
                    .setPrincipal(SessionPrincipal.newBuilder()
                        .setSessionId(sessionId)
                        .setUserId(42L)
                        .setSessionVersion(7L)
                        .build())
                    .build();
                case "expired:42:7" -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED)
                    .setPrincipal(SessionPrincipal.newBuilder()
                        .setSessionId(sessionId)
                        .setUserId(42L)
                        .setSessionVersion(7L)
                        .build())
                    .build();
                case "replaced:42:7" -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED)
                    .setPrincipal(SessionPrincipal.newBuilder()
                        .setSessionId(sessionId)
                        .setUserId(42L)
                        .setSessionVersion(7L)
                        .build())
                    .build();
                case "error:42:7" -> throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
                default -> ResolveSessionResponse.newBuilder()
                    .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID)
                    .build();
            };
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private static final class InMemoryDirectory implements UserChannelDirectory<io.netty.channel.Channel> {
        private final ConcurrentHashMap<Long, io.netty.channel.Channel> channels = new ConcurrentHashMap<>();

        @Override
        public void bind(long userId, io.netty.channel.Channel channelRef) {
            channels.put(userId, channelRef);
        }

        @Override
        public Optional<io.netty.channel.Channel> find(long userId) {
            return Optional.ofNullable(channels.get(userId));
        }

        @Override
        public boolean unbind(long userId, io.netty.channel.Channel channelRef) {
            return channels.remove(userId, channelRef);
        }
    }
}
