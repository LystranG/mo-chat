package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.chat.GroupMessageDelivery;
import com.github.lystran.mochat.logic.chat.MessageDeliveryStatus;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.chat.PrivateConversationProgressTracker;
import com.github.lystran.mochat.logic.chat.PrivateMessageDelivery;
import com.github.lystran.mochat.logic.chat.SenderAckPublisher;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.ReplayOfflineMessagesCommand;
import com.github.lystran.mochat.protocol.internal.message.v1.SendGroupMessageCommand;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageCommand;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Singleton;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MessageServiceGrpcConnectivityTest {
    private static final String SPEC_NAME = "message-service-grpc-connectivity";

    @Test
    void privateSendUsesInjectedIngestGraphAndReturnsGeneratedMetadata() throws Exception {
        byte[] nonce = new byte[] {1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23};
        ByteString ciphertext = ByteString.copyFromUtf8("ciphertext-private-34");
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var rocketMqProducer = context.getBean(RecordingRocketMqState.class);
            var response = stub.sendPrivateMessage(SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1001L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(nonce))
                .setCiphertext(ciphertext)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1001L, response.getClientMsgId());
            assertEquals(9_001L, response.getMsgId());
            assertEquals(77L, response.getSeq());
            assertEquals(1, rocketMqProducer.publishCount());

            MessageAcceptedEvent acceptedEvent = rocketMqProducer.lastEnvelope();
            assertNotNull(acceptedEvent);
            assertEquals("private", acceptedEvent.kind());
            Mochat.PrivateMessageReq payload = Mochat.PrivateMessageReq.parseFrom(
                Base64.getDecoder().decode(acceptedEvent.payloadBase64())
            );
            assertEquals(55L, payload.getConversationId());
            assertEquals(1001L, payload.getClientMsgId());
            assertEquals(34L, payload.getToUid());
            assertArrayEquals(nonce, payload.getNonce().toByteArray());
            assertEquals(ciphertext, payload.getCiphertext());
        }
    }

    @Test
    void duplicatePrivateSendReusesOriginalMsgIdAndSeqWithoutRepublishing() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var rocketMqProducer = context.getBean(RecordingRocketMqState.class);

            var first = stub.sendPrivateMessage(SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1001L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("ciphertext"))
                .build());
            var duplicate = stub.sendPrivateMessage(SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1001L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8("ciphertext"))
                .build());

            assertTrue(first.getAccepted());
            assertTrue(duplicate.getAccepted());
            assertEquals(first.getMsgId(), duplicate.getMsgId());
            assertEquals(first.getSeq(), duplicate.getSeq());
            assertEquals(1, rocketMqProducer.publishCount());
        }
    }

    @Test
    void privateSendReturnsAcceptedMetadataAfterRealtimeDispatchAttempt() {
        byte[] nonce = new byte[] {2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24};
        ByteString ciphertext = ByteString.copyFromUtf8("ciphertext-consistency-window");
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var dispatcher = context.getBean(RecordingMessageRecipientDispatcher.class);

            dispatcher.enqueuePrivateStatuses(MessageDeliveryStatus.DELIVERED);
            var response = stub.sendPrivateMessage(SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1002L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .setNonce(ByteString.copyFrom(nonce))
                .setCiphertext(ciphertext)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1002L, response.getClientMsgId());
            assertEquals(9_001L, response.getMsgId());
            assertEquals(77L, response.getSeq());
            assertEquals(1, dispatcher.privateDeliveries().size());

            PrivateMessageDelivery delivery = dispatcher.privateDeliveries().getFirst();
            assertEquals(55L, delivery.conversationId());
            assertEquals(9_001L, delivery.msgId());
            assertEquals(77L, delivery.seq());
            assertEquals(21L, delivery.senderUid());
            assertEquals(34L, delivery.recipientUid());
        }
    }

    @Test
    void groupSendUsesInjectedIngestGraphAndEncodesGroupPayload() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var rocketMqProducer = context.getBean(RecordingRocketMqState.class);
            var response = stub.sendGroupMessage(SendGroupMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(2002L)
                .setConversationId(88L)
                .setGroupId(701L)
                .setText("hello-group-701")
                .build());

            assertTrue(response.getAccepted());
            assertEquals(2002L, response.getClientMsgId());
            assertEquals(9_001L, response.getMsgId());
            assertEquals(77L, response.getSeq());
            assertEquals(1, rocketMqProducer.publishCount());

            MessageAcceptedEvent acceptedEvent = rocketMqProducer.lastEnvelope();
            assertNotNull(acceptedEvent);
            assertEquals("group", acceptedEvent.kind());
            assertEquals(701L, acceptedEvent.groupId());
            Mochat.GroupMessageReq payload = Mochat.GroupMessageReq.parseFrom(
                Base64.getDecoder().decode(acceptedEvent.payloadBase64())
            );
            assertEquals(88L, payload.getConversationId());
            assertEquals(2002L, payload.getClientMsgId());
            assertEquals(701L, payload.getGroupId());
            assertEquals("hello-group-701", payload.getText());
        }
    }

    @Test
    void replayOfflineMessagesDrainsQueueAndReturnsActualReplayedCount() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var offlineQueue = context.getBean(RecordingOfflineQueue.class);
            var dispatcher = context.getBean(RecordingMessageRecipientDispatcher.class);

            offlineQueue.seed(34L,
                replayablePrivatePayload(55L, 9_001L, 77L, 1_710_000_000_000L, 21L, 34L, "cipher-1"),
                replayablePrivatePayload(55L, 9_002L, 78L, 1_710_000_000_001L, 21L, 34L, "cipher-2")
            );
            dispatcher.enqueuePrivateStatuses(MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.DELIVERED);

            var response = stub.replayOfflineMessages(ReplayOfflineMessagesCommand.newBuilder()
                .setUserId(34L)
                .setMaxBatchSize(10)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(2, response.getReplayedCount());
            assertEquals(List.of(), offlineQueue.remaining(34L));
        }
    }

    @Test
    void replayOfflineMessagesReEnqueuesCurrentAndRemainingPayloadsWhenDispatchFails() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var offlineQueue = context.getBean(RecordingOfflineQueue.class);
            var dispatcher = context.getBean(RecordingMessageRecipientDispatcher.class);
            String firstPayload = replayablePrivatePayload(55L, 9_001L, 77L, 1_710_000_000_000L, 21L, 34L, "cipher-1");
            String secondPayload = replayablePrivatePayload(55L, 9_002L, 78L, 1_710_000_000_001L, 21L, 34L, "cipher-2");
            String thirdPayload = replayablePrivatePayload(55L, 9_003L, 79L, 1_710_000_000_002L, 21L, 34L, "cipher-3");

            offlineQueue.seed(34L, firstPayload, secondPayload, thirdPayload);
            dispatcher.enqueuePrivateStatuses(MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.WRITE_FAILED);

            var response = stub.replayOfflineMessages(ReplayOfflineMessagesCommand.newBuilder()
                .setUserId(34L)
                .setMaxBatchSize(10)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1, response.getReplayedCount());
            assertEquals(List.of(secondPayload, thirdPayload), offlineQueue.remaining(34L));
        }
    }

    @Test
    void replayOfflineMessagesDrainsGroupEnvelopeAndReplaysThroughDispatcher() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "mochat.message-service.dependencies.api-grpc-enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false,
            "mochat.message-service.dependencies.redis-enabled", false,
            "mochat.message-service.dependencies.mq-enabled", false
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var offlineQueue = context.getBean(RecordingOfflineQueue.class);
            var dispatcher = context.getBean(RecordingMessageRecipientDispatcher.class);

            offlineQueue.seed(33L, replayableGroupPayload(88L, 9_101L, 81L, 1_710_000_000_123L, 21L, 701L, "hello-replay-group"));
            dispatcher.enqueueGroupStatuses(Map.of(33L, MessageDeliveryStatus.DELIVERED));

            var response = stub.replayOfflineMessages(ReplayOfflineMessagesCommand.newBuilder()
                .setUserId(33L)
                .setMaxBatchSize(10)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1, response.getReplayedCount());
            assertEquals(List.of(), offlineQueue.remaining(33L));
            assertEquals(1, dispatcher.groupDeliveries().size());
            GroupMessageDelivery replayed = dispatcher.groupDeliveries().getFirst();
            assertEquals(88L, replayed.conversationId());
            assertEquals(9_101L, replayed.msgId());
            assertEquals(81L, replayed.seq());
            assertEquals(21L, replayed.senderUid());
            assertEquals(701L, replayed.groupId());
            assertEquals(List.of(33L), replayed.recipientUids());
        }
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestGrpcClientFactory {
        @Singleton
        MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return MessageCommandApiGrpc.newBlockingStub(channel);
        }
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class ConnectivityTestBeans {
        @Singleton
        ConversationLock conversationLock() {
            return new JucConversationLock();
        }

        @Singleton
        IdempotencyStore idempotencyStore() {
            return new InMemoryIdempotencyStore();
        }

        @Singleton
        ConversationSeqGenerator conversationSeqGenerator() {
            return new RecordingConversationSeqGenerator(77L);
        }

        @Singleton
        IdGenerator idGenerator() {
            return new RecordingIdGenerator(9_001L);
        }

        @Singleton
        RecordingRocketMqState recordingRocketMqState() {
            return new RecordingRocketMqState();
        }

        @Singleton
        @Replaces(RocketMqProducer.class)
        RocketMqProducer rocketMqProducer(RecordingRocketMqState recordingRocketMqState) {
            RocketMqProducer rocketMqProducer = Mockito.mock(RocketMqProducer.class);
            when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenAnswer(invocation -> {
                recordingRocketMqState.record(invocation.getArgument(0));
                return true;
            });
            return rocketMqProducer;
        }

        @Singleton
        @Replaces(MessageSendPolicyGateway.class)
        MessageSendPolicyGateway messageSendPolicyGateway() {
            return new MessageSendPolicyGateway() {
                @Override
                public void validatePrivateMessage(long conversationId, long senderUid, long recipientUid) {
                }

                @Override
                public List<Long> resolveGroupRecipientUids(long groupId, long senderUid) {
                    return List.of(senderUid, 22L, 33L);
                }
            };
        }

        @Singleton
        SenderAckPublisher senderAckPublisher() {
            return (senderUid, clientMsgId, msgId, seq, serverTimeMs) -> {
            };
        }

        @Singleton
        RecordingMessageRecipientDispatcher recordingMessageRecipientDispatcher() {
            return new RecordingMessageRecipientDispatcher();
        }

        @Singleton
        MessageRecipientDispatcher messageRecipientDispatcher(RecordingMessageRecipientDispatcher dispatcher) {
            return dispatcher;
        }

        @Singleton
        @Replaces(PrivateConversationProgressTracker.class)
        PrivateConversationProgressTracker privateConversationProgressTracker() {
            return (conversationId, peerUidLow, peerUidHigh, seq) -> {
            };
        }

        @Singleton
        RecordingOfflineQueue offlineQueue() {
            return new RecordingOfflineQueue();
        }
    }

    static final class RecordingRocketMqState {
        private final AtomicInteger publishCount = new AtomicInteger();
        private volatile MessageAcceptedEvent lastEnvelope;

        void record(MessageAcceptedEvent acceptedEvent) {
            lastEnvelope = acceptedEvent;
            publishCount.incrementAndGet();
        }

        int publishCount() {
            return publishCount.get();
        }

        MessageAcceptedEvent lastEnvelope() {
            return lastEnvelope;
        }
    }

    static final class RecordingConversationSeqGenerator implements ConversationSeqGenerator {
        private final AtomicLong nextValue;

        RecordingConversationSeqGenerator(long firstValue) {
            this.nextValue = new AtomicLong(firstValue);
        }

        @Override
        public long next(long conversationId) {
            return nextValue.getAndIncrement();
        }
    }

    static final class RecordingIdGenerator implements IdGenerator {
        private final AtomicLong nextValue;

        RecordingIdGenerator(long firstValue) {
            this.nextValue = new AtomicLong(firstValue);
        }

        @Override
        public long nextId() {
            return nextValue.getAndIncrement();
        }
    }

    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final ConcurrentMap<String, StoredSendResult> values = new ConcurrentHashMap<>();

        @Override
        public Optional<StoredSendResult> find(long senderUid, long clientMsgId) {
            return Optional.ofNullable(values.get(key(senderUid, clientMsgId)));
        }

        @Override
        public void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq) {
            values.putIfAbsent(key(senderUid, clientMsgId), new StoredSendResult(msgId, seq));
        }

        private static String key(long senderUid, long clientMsgId) {
            return senderUid + ":" + clientMsgId;
        }
    }

    static final class RecordingOfflineQueue implements OfflineQueue {
        private final ConcurrentMap<Long, ArrayDeque<String>> payloadsByUser = new ConcurrentHashMap<>();

        void seed(long userId, String... payloads) {
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            for (String payload : payloads) {
                queue.addLast(payload);
            }
        }

        List<String> remaining(long userId) {
            return new ArrayList<>(payloadsByUser.getOrDefault(userId, new ArrayDeque<>()));
        }

        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            while (queue.size() >= maxQueueSize) {
                queue.pollFirst();
            }
            queue.addLast(payload);
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            List<String> drained = new ArrayList<>();
            while (drained.size() < maxItems && !queue.isEmpty()) {
                drained.add(queue.removeFirst());
            }
            if (queue.isEmpty()) {
                payloadsByUser.remove(userId, queue);
            }
            return drained;
        }
    }

    static final class RecordingMessageRecipientDispatcher implements MessageRecipientDispatcher {
        private final ArrayDeque<MessageDeliveryStatus> privateStatuses = new ArrayDeque<>();
        private final ArrayDeque<Map<Long, MessageDeliveryStatus>> groupStatuses = new ArrayDeque<>();
        private final ArrayList<PrivateMessageDelivery> privateDeliveries = new ArrayList<>();
        private final ArrayList<GroupMessageDelivery> groupDeliveries = new ArrayList<>();

        void enqueuePrivateStatuses(MessageDeliveryStatus... statuses) {
            for (MessageDeliveryStatus status : statuses) {
                privateStatuses.addLast(status);
            }
        }

        void enqueueGroupStatuses(Map<Long, MessageDeliveryStatus> statuses) {
            groupStatuses.addLast(statuses);
        }

        List<GroupMessageDelivery> groupDeliveries() {
            return groupDeliveries;
        }

        List<PrivateMessageDelivery> privateDeliveries() {
            return privateDeliveries;
        }

        @Override
        public MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery) {
            privateDeliveries.add(delivery);
            return privateStatuses.isEmpty() ? MessageDeliveryStatus.DELIVERED : privateStatuses.removeFirst();
        }

        @Override
        public Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery) {
            groupDeliveries.add(delivery);
            if (!groupStatuses.isEmpty()) {
                return groupStatuses.removeFirst();
            }
            return delivery.recipientUids().stream()
                .collect(java.util.stream.Collectors.toMap(
                    java.util.function.Function.identity(),
                    ignored -> MessageDeliveryStatus.DELIVERED
                ));
        }
    }

    private static String replayablePrivatePayload(
        long conversationId,
        long msgId,
        long seq,
        long serverTimeMs,
        long senderUid,
        long recipientUid,
        String ciphertext
    ) {
        byte[] body = Mochat.ChatMessageDelivery.newBuilder()
            .setConversationId(conversationId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .setFromUid(senderUid)
            .setPrivatePayload(Mochat.PrivatePayload.newBuilder()
                .setToUid(recipientUid)
                .setNonce(ByteString.copyFrom(new byte[12]))
                .setCiphertext(ByteString.copyFromUtf8(ciphertext))
                .build())
            .build()
            .toByteArray();
        return MsgType.PRIVATE_MESSAGE.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(body);
    }

    private static String replayableGroupPayload(
        long conversationId,
        long msgId,
        long seq,
        long serverTimeMs,
        long senderUid,
        long groupId,
        String text
    ) {
        byte[] body = Mochat.ChatMessageDelivery.newBuilder()
            .setConversationId(conversationId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .setFromUid(senderUid)
            .setGroupPayload(Mochat.GroupPayload.newBuilder()
                .setGroupId(groupId)
                .setText(text)
                .build())
            .build()
            .toByteArray();
        return MsgType.GROUP_MESSAGE.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(body);
    }
}
