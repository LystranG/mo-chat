package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.accessgateway.grpc.AccessGatewayInternalGrpcService;
import com.github.lystran.mochat.accessgateway.runtime.LocalConnectionStateSnapshot;
import com.github.lystran.mochat.accessgateway.runtime.LocalGatewayConnectionDirectory;
import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.session.InMemoryChannelSessionRegistry;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.connection.InboundRouterHandler;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway;
import com.github.lystran.mochat.logic.chat.MessageServiceOfflineReplayService;
import com.github.lystran.mochat.logic.chat.PrivateConversationProgressTracker;
import com.github.lystran.mochat.logic.chat.SenderAckPublisher;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageRecipientDispatcher;
import com.github.lystran.mochat.messageservice.grpc.MessageCommandGrpcService;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageAck;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageCommand;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.api.sync.RedisCommands;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageServiceCrossGatewayRoutingIntegrationTest {
    @Test
    void privateSendTargetsRemoteGatewayAndWritesDeliveryToBoundRecipientChannel() throws Exception {
        GatewayStubRegistry.clear();
        try (
            GatewayHarness gatewayA = GatewayHarness.start();
            GatewayHarness gatewayB = GatewayHarness.start()
        ) {
            GatewayStubRegistry.register("gateway-a", gatewayA.stub());
            GatewayStubRegistry.register("gateway-b", gatewayB.stub());

            gatewayA.bind("active:21:3", 201L, 34L);
            EmbeddedChannel recipientChannel = gatewayB.bind("active:34:5", 202L, 21L);

            RecordingRouteStore routeStore = new RecordingRouteStore();
            routeStore.put(
                34L,
                routeRecord(
                    "gateway-b",
                    recipientChannel.id().asLongText(),
                    "active:34:5",
                    5L,
                    11L
                )
            );

            MessageCommandGrpcService messageCommandGrpcService = messageCommandGrpcService(routeStore);
            var response = invokeSendPrivateMessage(messageCommandGrpcService, SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1001L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(new byte[] {1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23}))
                .setCiphertext(ByteString.copyFromUtf8("ciphertext-cross-gateway"))
                .build());

            assertTrue(response.getAccepted());
            ArgumentCaptor<DeliverToConnectionRequest> requestCaptor = ArgumentCaptor.forClass(DeliverToConnectionRequest.class);
            verify(gatewayB.stub()).deliverToConnection(requestCaptor.capture());
            verify(gatewayA.stub(), never()).deliverToConnection(any());
            assertEquals(recipientChannel.id().asLongText(), requestCaptor.getValue().getConnectionId());
            assertEquals("active:34:5", requestCaptor.getValue().getSessionId());
            assertEquals(5L, requestCaptor.getValue().getSessionVersion());
            assertEquals(11L, requestCaptor.getValue().getExpectedRouteEpoch());
            waitForPendingTasks(recipientChannel);

            assertEquals(
                DeliveryStatus.DELIVERY_STATUS_DELIVERED,
                gatewayB.lastDeliverResponse().get().getStatus()
            );
            ByteBuf frame = recipientChannel.readOutbound();
            assertNotNull(frame);
            try {
                Mochat.ChatMessageDelivery delivery = decodePrivateDelivery(frame);
                assertEquals(55L, delivery.getConversationId());
                assertEquals(21L, delivery.getFromUid());
                assertEquals(34L, delivery.getPrivatePayload().getToUid());
                assertEquals("ciphertext-cross-gateway", delivery.getPrivatePayload().getCiphertext().toStringUtf8());
            } finally {
                frame.release();
            }
            assertNull(gatewayA.readOutbound());
        } finally {
            GatewayStubRegistry.clear();
        }
    }

    @Test
    void staleRouteAfterSingleRefreshFallsBackToOfflineQueue() throws Exception {
        GatewayStubRegistry.clear();
        try (
            GatewayHarness gatewayA = GatewayHarness.start();
            GatewayHarness gatewayB = GatewayHarness.start()
        ) {
            GatewayStubRegistry.register("gateway-a", gatewayA.stub());
            GatewayStubRegistry.register("gateway-b", gatewayB.stub());

            EmbeddedChannel staleRecipientChannel = gatewayA.bind("active:34:5", 202L, 21L);
            RecordingRouteStore routeStore = new RecordingRouteStore();
            routeStore.script(
                34L,
                routeRecord(
                    "gateway-a",
                    staleRecipientChannel.id().asLongText(),
                    "active:34:5",
                    5L,
                    10L
                ),
                routeRecord(
                    "gateway-b",
                    "missing-connection",
                    "active:34:6",
                    6L,
                    12L
                )
            );
            RecordingOfflineQueue offlineQueue = new RecordingOfflineQueue();

            MessageCommandGrpcService messageCommandGrpcService = messageCommandGrpcService(routeStore, offlineQueue);
            var response = invokeSendPrivateMessage(messageCommandGrpcService, SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1002L)
                .setConversationId(56L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(new byte[] {2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24}))
                .setCiphertext(ByteString.copyFromUtf8("ciphertext-fallback"))
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1, offlineQueue.entries().size());
            RecordingOfflineQueue.Entry offlineEntry = offlineQueue.entries().getFirst();
            assertEquals(34L, offlineEntry.userId());
            Mochat.ChatMessageDelivery offlineDelivery = decodeReplayablePrivateDelivery(offlineEntry.payload());
            assertEquals(56L, offlineDelivery.getConversationId());
            assertEquals(21L, offlineDelivery.getFromUid());
            assertEquals(34L, offlineDelivery.getPrivatePayload().getToUid());
            assertEquals("ciphertext-fallback", offlineDelivery.getPrivatePayload().getCiphertext().toStringUtf8());
            assertNull(staleRecipientChannel.readOutbound());
            assertNull(gatewayB.readOutbound());
        } finally {
            GatewayStubRegistry.clear();
        }
    }

    private static Mochat.ChatMessageDelivery decodePrivateDelivery(ByteBuf frame) throws Exception {
        assertEquals(0x4D4F4348, frame.readInt());
        assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
        assertEquals(MsgType.PRIVATE_MESSAGE.code(), frame.readUnsignedByte());
        assertEquals(SerializerType.PROTOBUF.code(), frame.readUnsignedByte());
        int bodyLength = frame.readInt();
        byte[] body = new byte[bodyLength];
        frame.readBytes(body);
        return Mochat.ChatMessageDelivery.parseFrom(body);
    }

    private static Mochat.ChatMessageDelivery decodeReplayablePrivateDelivery(String payload) throws Exception {
        String[] segments = payload.split("\\|", 3);
        assertEquals(MsgType.PRIVATE_MESSAGE.name(), segments[0]);
        assertEquals(SerializerType.PROTOBUF.name(), segments[1]);
        return Mochat.ChatMessageDelivery.parseFrom(Base64.getDecoder().decode(segments[2]));
    }

    private static String routeRecord(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        long routeEpoch
    ) {
        return String.join(";",
            "gatewayPod=" + encode(gatewayPod),
            "connectionId=" + encode(connectionId),
            "sessionId=" + encode(sessionId),
            "sessionVersion=" + sessionVersion,
            "routeEpoch=" + routeEpoch,
            "leaseDurationSeconds=60",
            "leaseExpiresAtEpochMilli=1710000000000"
        );
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("bind"))
                .build()
                .toByteArray()
        );
    }

    private static SessionRouteWriter<io.netty.channel.Channel> routeWriter() {
        return (resolvedSession, channelRef) -> new PersistedSessionRoute(11L);
    }

    private static MessageCommandGrpcService messageCommandGrpcService(RecordingRouteStore routeStore) {
        return messageCommandGrpcService(routeStore, noOpOfflineQueue());
    }

    private static MessageCommandGrpcService messageCommandGrpcService(
        RecordingRouteStore routeStore,
        OfflineQueue offlineQueue
    ) {
        MessageRecipientDispatcher messageRecipientDispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands(routeStore),
            GatewayStubRegistry::lookup,
            gatewayPod -> gatewayPod
        );
        MessageIngestService messageIngestService = new MessageIngestService(
            new JucConversationLock(),
            new InMemoryIdempotencyStore(),
            conversationId -> 77L,
            new IncrementingIdGenerator(9_001L),
            Clock.systemUTC(),
            rocketMqProducer(),
            messageSendPolicyGateway(),
            senderAckPublisher(),
            messageRecipientDispatcher,
            privateConversationProgressTracker(),
            offlineQueue
        );
        return new MessageCommandGrpcService(
            messageIngestService,
            new MessageServiceOfflineReplayService(offlineQueue, messageRecipientDispatcher)
        );
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> redisCommands(RecordingRouteStore routeStore) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "get" -> routeStore.get((String) args[0]);
                case "toString" -> "MessageServiceCrossGatewayRouteStore";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
            }
        );
    }

    private static RocketMqProducer rocketMqProducer() {
        RocketMqProducer producer = Mockito.mock(RocketMqProducer.class);
        when(producer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
        return producer;
    }

    private static MessageSendPolicyGateway messageSendPolicyGateway() {
        return new MessageSendPolicyGateway() {
            @Override
            public void validatePrivateMessage(long conversationId, long senderUid, long recipientUid) {
            }

            @Override
            public List<Long> resolveGroupRecipientUids(long groupId, long senderUid) {
                return List.of(senderUid, 34L);
            }
        };
    }

    private static SenderAckPublisher senderAckPublisher() {
        return (senderUid, clientMsgId, msgId, seq, serverTimeMs) -> {
        };
    }

    private static PrivateConversationProgressTracker privateConversationProgressTracker() {
        return (conversationId, peerUidLow, peerUidHigh, seq) -> {
        };
    }

    private static OfflineQueue noOpOfflineQueue() {
        return new OfflineQueue() {
            @Override
            public void enqueue(long userId, String payload, int maxQueueSize) {
            }

            @Override
            public List<String> drain(long userId, int maxItems) {
                return List.of();
            }
        };
    }

    private static SessionResolver sessionResolver() {
        return new SessionResolver() {
            @Override
            public Optional<ResolvedSession> resolveSession(String sessionId) {
                return switch (sessionId) {
                    case "active:21:3" -> Optional.of(new ResolvedSession("active:21:3", 21L, 3L));
                    case "active:34:5" -> Optional.of(new ResolvedSession("active:34:5", 34L, 5L));
                    default -> Optional.empty();
                };
            }

            @Override
            public Optional<Long> resolveUserId(String sessionId) {
                return resolveSession(sessionId).map(ResolvedSession::userId);
            }
        };
    }

    private static DeliverToConnectionResponse invokeDeliverToConnection(
        AccessGatewayInternalGrpcService service,
        DeliverToConnectionRequest request
    ) {
        AtomicReference<DeliverToConnectionResponse> responseRef = new AtomicReference<>();
        service.deliverToConnection(request, new StreamObserver<>() {
            @Override
            public void onNext(DeliverToConnectionResponse value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
        return responseRef.get();
    }

    private static SendPrivateMessageAck invokeSendPrivateMessage(
        MessageCommandGrpcService service,
        SendPrivateMessageCommand request
    ) {
        AtomicReference<SendPrivateMessageAck> responseRef = new AtomicReference<>();
        service.sendPrivateMessage(request, new StreamObserver<>() {
            @Override
            public void onNext(SendPrivateMessageAck value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
        return responseRef.get();
    }

    private static void waitForPendingTasks(EmbeddedChannel channel) throws InterruptedException {
        for (int index = 0; index < 50; index++) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }

    static final class RecordingRouteStore {
        private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, List<String>> scriptedValues = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicLong> scriptedIndexes = new ConcurrentHashMap<>();

        void put(long userId, String payload) {
            values.put("online:user:" + userId, payload);
        }

        void script(long userId, String... payloads) {
            String key = "online:user:" + userId;
            scriptedValues.put(key, List.of(payloads));
            scriptedIndexes.put(key, new AtomicLong());
        }

        String get(String key) {
            List<String> scripted = scriptedValues.get(key);
            if (scripted != null && !scripted.isEmpty()) {
                long readIndex = scriptedIndexes.getOrDefault(key, new AtomicLong()).getAndIncrement();
                return scripted.get((int) Math.min(readIndex, scripted.size() - 1L));
            }
            return values.get(key);
        }
    }

    static final class RecordingOfflineQueue implements OfflineQueue {
        private final java.util.ArrayDeque<Entry> entries = new java.util.ArrayDeque<>();

        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
            entries.addLast(new Entry(userId, payload, maxQueueSize));
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            return List.of();
        }

        java.util.ArrayDeque<Entry> entries() {
            return entries;
        }

        record Entry(long userId, String payload, int maxQueueSize) {
        }
    }

    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final ConcurrentMap<String, StoredSendResult> values = new ConcurrentHashMap<>();

        @Override
        public Optional<StoredSendResult> find(long senderUid, long clientMsgId) {
            return Optional.ofNullable(values.get(senderUid + ":" + clientMsgId));
        }

        @Override
        public void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq) {
            values.putIfAbsent(senderUid + ":" + clientMsgId, new StoredSendResult(msgId, seq));
        }
    }

    static final class IncrementingIdGenerator implements IdGenerator {
        private final AtomicLong nextId;

        IncrementingIdGenerator(long firstId) {
            this.nextId = new AtomicLong(firstId);
        }

        @Override
        public long nextId() {
            return nextId.getAndIncrement();
        }
    }

    private record GatewayHarness(
        TestLocalGatewayConnectionDirectory directory,
        SessionBindingHandler sessionBindingHandler,
        OutboundEventSubscriber outboundEventSubscriber,
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub,
        AtomicReference<EmbeddedChannel> lastBoundChannel,
        AtomicReference<DeliverToConnectionResponse> lastDeliverResponse
    ) implements AutoCloseable {
        static GatewayHarness start() {
            TestLocalGatewayConnectionDirectory directory = new TestLocalGatewayConnectionDirectory();
            SessionResolver sessionResolver = sessionResolver();
            SessionBindingHandler sessionBindingHandler = new SessionBindingHandler(
                sessionResolver,
                new InMemoryChannelSessionRegistry<>(directory),
                routeWriter()
            );
            OutboundEventSubscriber outboundEventSubscriber = new OutboundEventSubscriber(
                new InProcessEventBus(),
                directory,
                noOpOfflineQueue()
            );
            outboundEventSubscriber.start();
            AccessGatewayInternalGrpcService service = new AccessGatewayInternalGrpcService(directory, () -> sessionResolver);
            AtomicReference<DeliverToConnectionResponse> lastDeliverResponse = new AtomicReference<>();
            AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
                Mockito.mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
            when(stub.deliverToConnection(any())).thenAnswer(invocation -> {
                DeliverToConnectionResponse response = invokeDeliverToConnection(service, invocation.getArgument(0));
                lastDeliverResponse.set(response);
                return response;
            });
            return new GatewayHarness(
                directory,
                sessionBindingHandler,
                outboundEventSubscriber,
                stub,
                new AtomicReference<>(),
                lastDeliverResponse
            );
        }

        EmbeddedChannel bind(String sessionId, long conversationId, long toUid) throws Exception {
            RecordingInboundHandler downstream = new RecordingInboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(sessionBindingHandler, downstream);
            channel.writeInbound(privateMessage(sessionId, conversationId, toUid));
            waitForPendingTasks(channel);
            lastBoundChannel.set(channel);
            return channel;
        }

        Object readOutbound() {
            EmbeddedChannel channel = lastBoundChannel.get();
            return channel == null ? null : channel.readOutbound();
        }

        @Override
        public void close() throws Exception {
            outboundEventSubscriber.close();
            EmbeddedChannel channel = lastBoundChannel.get();
            if (channel != null) {
                channel.close();
            }
        }
    }

    static final class GatewayStubRegistry {
        private static final ConcurrentMap<String, AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub> STUBS =
            new ConcurrentHashMap<>();

        private GatewayStubRegistry() {
        }

        static void register(String targetAddress, AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub) {
            STUBS.put(targetAddress, stub);
        }

        static AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub lookup(String targetAddress) {
            AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub = STUBS.get(targetAddress);
            if (stub == null) {
                throw new IllegalArgumentException("No gateway stub registered for targetAddress=" + targetAddress);
            }
            return stub;
        }

        static void clear() {
            STUBS.clear();
        }
    }

    static final class TestLocalGatewayConnectionDirectory implements UserChannelDirectory<io.netty.channel.Channel>, LocalGatewayConnectionDirectory {
        private final ConcurrentMap<Long, io.netty.channel.Channel> channelsByUserId = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, io.netty.channel.Channel> channelsByConnectionId = new ConcurrentHashMap<>();

        @Override
        public void bind(long userId, io.netty.channel.Channel channelRef) {
            channelsByUserId.put(userId, channelRef);
            channelsByConnectionId.put(channelRef.id().asLongText(), channelRef);
        }

        @Override
        public Optional<io.netty.channel.Channel> find(long userId) {
            return Optional.ofNullable(channelsByUserId.get(userId));
        }

        @Override
        public boolean unbind(long userId, io.netty.channel.Channel channelRef) {
            boolean removedByUser = channelsByUserId.remove(userId, channelRef);
            boolean removedByConnection = channelsByConnectionId.remove(channelRef.id().asLongText(), channelRef);
            return removedByUser || removedByConnection;
        }

        @Override
        public boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason) {
            io.netty.channel.Channel channel = channelsByConnectionId.get(connectionId);
            if (channel == null || !channel.isOpen()) {
                return false;
            }
            Long boundUserId = channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
            Long boundSessionVersion = channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get();
            Long boundRouteEpoch = channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get();
            if (boundUserId == null || boundSessionVersion == null || boundRouteEpoch == null) {
                return false;
            }
            if (boundUserId != userId || boundSessionVersion != sessionVersion || boundRouteEpoch != expectedRouteEpoch) {
                return false;
            }
            channel.close();
            return true;
        }

        @Override
        public Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId) {
            io.netty.channel.Channel channel = channelsByConnectionId.get(connectionId);
            if (channel == null || !channel.isOpen()) {
                return Optional.empty();
            }
            Long boundUserId = channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
            String sessionId = channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get();
            Long sessionVersion = channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get();
            Long routeEpoch = channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get();
            if (boundUserId == null || sessionId == null || sessionVersion == null || routeEpoch == null || boundUserId != userId) {
                return Optional.empty();
            }
            return Optional.of(new LocalConnectionStateSnapshot(
                sessionId,
                sessionVersion,
                routeEpoch,
                SessionBindingHandler.hasActiveRouteOwnership(channel)
            ));
        }

        @Override
        public int closeBoundConnections() {
            int closed = 0;
            for (io.netty.channel.Channel channel : channelsByConnectionId.values()) {
                if (channel != null && channel.isOpen()) {
                    closed++;
                    channel.close();
                }
            }
            return closed;
        }
    }

    private static final class RecordingInboundHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
        }
    }
}
