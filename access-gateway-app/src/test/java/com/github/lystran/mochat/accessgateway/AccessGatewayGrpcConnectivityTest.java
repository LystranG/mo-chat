package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.connection.InboundRouterHandler;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.internal.common.v1.DeliveryEnvelope;
import com.github.lystran.mochat.protocol.internal.common.v1.PrivateDeliveryContent;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import io.grpc.Channel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayGrpcConnectivityTest {
    private static final String DELIVERY_GRPC_SPEC = "access-gateway-delivery-grpc";

    @Test
    void deliversRequestsOverMicronautGrpcServerChannel() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", DELIVERY_GRPC_SPEC,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "grpc.server.port", 0
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            var stub = context.getBean(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler);
            var validEnvelope = DeliveryEnvelope.newBuilder()
                .setConversationId(10L)
                .setMsgId(11L)
                .setSeq(12L)
                .setServerTimeMs(13L)
                .setFromUid(14L)
                .setPrivateContent(PrivateDeliveryContent.newBuilder()
                    .setToUid(15L)
                    .build())
                .build();
            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            var delivered = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId(channel.id().asLongText())
                .setSessionId("active:42:7")
                .setSessionVersion(7L)
                .setExpectedRouteEpoch(11L)
                .setEnvelope(validEnvelope)
                .build());
            var offline = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId("missing-connection")
                .setSessionId("active:42:7")
                .setSessionVersion(7L)
                .setExpectedRouteEpoch(11L)
                .setEnvelope(validEnvelope)
                .build());
            var stale = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId(channel.id().asLongText())
                .setSessionId("active:42:7")
                .setSessionVersion(8L)
                .setExpectedRouteEpoch(11L)
                .setEnvelope(validEnvelope)
                .build());
            var writeFailed = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId(channel.id().asLongText())
                .setSessionId("active:42:7")
                .setSessionVersion(7L)
                .setExpectedRouteEpoch(11L)
                .build());

            assertEquals(DeliveryStatus.DELIVERY_STATUS_DELIVERED, delivered.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE, offline.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE, stale.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED, writeFailed.getStatus());
        }
    }

    @Test
    void deliverToConnectionReturnsRouteStaleWhenAuthoritySessionIsNoLongerActive() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", DELIVERY_GRPC_SPEC,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "grpc.server.port", 0
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            MutableSessionResolver sessionResolver = context.getBean(MutableSessionResolver.class);
            var stub = context.getBean(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler);
            var validEnvelope = DeliveryEnvelope.newBuilder()
                .setConversationId(10L)
                .setMsgId(11L)
                .setSeq(12L)
                .setServerTimeMs(13L)
                .setFromUid(14L)
                .setPrivateContent(PrivateDeliveryContent.newBuilder()
                    .setToUid(15L)
                    .build())
                .build();

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);
            sessionResolver.setResolvedSession(Optional.empty());

            var stale = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId(channel.id().asLongText())
                .setSessionId("active:42:7")
                .setSessionVersion(7L)
                .setExpectedRouteEpoch(11L)
                .setEnvelope(validEnvelope)
                .build());

            assertEquals(DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE, stale.getStatus());
        }
    }

    @Test
    void kickConnectionClosesMatchingLocalBoundChannel() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", "access-gateway-kick-grpc",
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false,
            "grpc.server.port", 0
        ))) {
            SessionBindingHandler sessionBindingHandler = context.getBean(SessionBindingHandler.class);
            var stub = context.getBean(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler);

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            var kicked = stub.kickConnection(KickConnectionRequest.newBuilder()
                .setUserId(42L)
                .setConnectionId(channel.id().asLongText())
                .setSessionVersion(7L)
                .setExpectedRouteEpoch(11L)
                .setReason("replaced_by_new_bind")
                .build());
            waitForPendingTasks(channel);

            assertTrue(kicked.getKicked());
            assertFalse(channel.isOpen());
        }
    }

    @Factory
    static final class TestGrpcClientFactory {
        @Singleton
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub accessGatewayDispatchApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return AccessGatewayDispatchApiGrpc.newBlockingStub(channel);
        }
    }

    @Factory
    @Requires(property = "spec.name", value = DELIVERY_GRPC_SPEC)
    static final class DeliveryGrpcTestFactory {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        MutableSessionResolver sessionResolver() {
            return new MutableSessionResolver(Optional.of(new ResolvedSession("active:42:7", 42L, 7L)));
        }

        @Singleton
        SessionRouteWriter<io.netty.channel.Channel> sessionRouteWriter() {
            return (resolvedSession, channelRef) -> new PersistedSessionRoute(11L);
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-kick-grpc")
    static final class KickGrpcTestFactory {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        SessionResolver sessionResolver() {
            return new SessionResolver() {
                @Override
                public Optional<ResolvedSession> resolveSession(String sessionId) {
                    if (!"active:42:7".equals(sessionId)) {
                        return Optional.empty();
                    }
                    return Optional.of(new ResolvedSession("active:42:7", 42L, 7L));
                }

                @Override
                public Optional<Long> resolveUserId(String sessionId) {
                    return resolveSession(sessionId).map(ResolvedSession::userId);
                }
            };
        }

        @Singleton
        SessionRouteWriter<io.netty.channel.Channel> sessionRouteWriter() {
            return (resolvedSession, channelRef) -> new PersistedSessionRoute(11L);
        }
    }

    private static InboundRouterHandler.InboundMessage privateMessage(String sessionId, long conversationId, long toUid) {
        return new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            com.github.lystran.mochat.protocol.proto.Mochat.PrivateMessageReq.newBuilder()
                .setSessionId(sessionId)
                .setClientMsgId(1001L)
                .setConversationId(conversationId)
                .setToUid(toUid)
                .addContents(com.github.lystran.mochat.protocol.proto.Mochat.MessageContent.newBuilder()
                    .setEncryptedText(com.github.lystran.mochat.protocol.proto.Mochat.EncryptedText.newBuilder()
                        .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                        .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                        .build())
                    .build())
                .build()
                .toByteArray()
        );
    }

    private static void waitForPendingTasks(EmbeddedChannel channel) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }

    private static final class MutableSessionResolver implements SessionResolver {
        private volatile Optional<ResolvedSession> resolvedSession;

        private MutableSessionResolver(Optional<ResolvedSession> resolvedSession) {
            this.resolvedSession = resolvedSession;
        }

        @Override
        public Optional<ResolvedSession> resolveSession(String sessionId) {
            Optional<ResolvedSession> currentSession = resolvedSession;
            return currentSession.filter(session -> session.sessionId().equals(sessionId));
        }

        @Override
        public Optional<Long> resolveUserId(String sessionId) {
            return resolveSession(sessionId).map(ResolvedSession::userId);
        }

        private void setResolvedSession(Optional<ResolvedSession> resolvedSession) {
            this.resolvedSession = resolvedSession;
        }
    }
}
