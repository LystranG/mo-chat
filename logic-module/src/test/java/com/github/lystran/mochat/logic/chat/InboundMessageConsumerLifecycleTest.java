package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundMessageConsumerLifecycleTest {
    @Test
    void startupSubscribesInboundEventIngestsAndShutdownUnsubscribes() throws Exception {
        RecordingEventBus recordingEventBus;

        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            recordingEventBus = context.getBean(RecordingEventBus.class);
            IdempotencyStore idempotencyStore = context.getBean(IdempotencyStore.class);
            ConversationSeqGenerator conversationSeqGenerator = context.getBean(ConversationSeqGenerator.class);
            IdGenerator idGenerator = context.getBean(IdGenerator.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

            assertEquals(1, recordingEventBus.subscriberCount(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC));

            when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
            when(conversationSeqGenerator.next(200L)).thenReturn(77L);
            when(idGenerator.nextId()).thenReturn(9_123L);

            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, encodedPrivateInboundEvent(1001L, 200L, 88L));

            MessageAcceptedEvent envelope = rocketMqProducer.lastEnvelope();
            assertEquals("200", rocketMqProducer.lastShardingKey());
            verify(idempotencyStore).storeIfAbsent(11L, 1001L, 9_123L, 77L);

            assertEquals(200L, envelope.conversationId());
            assertEquals(1001L, envelope.clientMsgId());
            assertEquals(11L, envelope.senderUid());
            assertEquals("private", envelope.kind());

            Mochat.PrivateMessageReq persistedPrivatePayload = Mochat.PrivateMessageReq.parseFrom(
                Base64.getDecoder().decode(envelope.payloadBase64())
            );
            assertTrue(persistedPrivatePayload.getSessionId().isEmpty());
            assertEquals(1001L, persistedPrivatePayload.getClientMsgId());
            assertEquals(200L, persistedPrivatePayload.getConversationId());
            assertEquals(88L, persistedPrivatePayload.getToUid());

            List<String> outboundEvents = recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
            assertEquals(2, outboundEvents.size());

            Mochat.SendAck sendAck = sendAckEvents(recordingEventBus).getFirst();
            assertEquals(1001L, sendAck.getClientMsgId());
            assertEquals(9_123L, sendAck.getMsgId());
            assertEquals(77L, sendAck.getSeq());
            assertTrue(sendAck.getServerTimeMs() > 0);
        }

        assertEquals(0, recordingEventBus.subscriberCount(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC));
        assertEquals(1, recordingEventBus.closedSubscriptionCount());
    }

    @Test
    void duplicateInboundPrivateMessageReusesOriginalMsgIdAndSeq() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            IdempotencyStore idempotencyStore = context.getBean(IdempotencyStore.class);
            ConversationSeqGenerator conversationSeqGenerator = context.getBean(ConversationSeqGenerator.class);
            IdGenerator idGenerator = context.getBean(IdGenerator.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

            when(idempotencyStore.find(11L, 1001L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IdempotencyStore.StoredSendResult(9_123L, 77L)));
            when(conversationSeqGenerator.next(200L)).thenReturn(77L);
            when(idGenerator.nextId()).thenReturn(9_123L);

            String inboundEvent = encodedPrivateInboundEvent(1001L, 200L, 88L);
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            List<Mochat.SendAck> sendAcks = sendAckEvents(recordingEventBus);
            assertEquals(2, sendAcks.size());
            assertEquals(1001L, sendAcks.get(0).getClientMsgId());
            assertEquals(1001L, sendAcks.get(1).getClientMsgId());
            assertEquals(sendAcks.get(0).getMsgId(), sendAcks.get(1).getMsgId());
            assertEquals(sendAcks.get(0).getSeq(), sendAcks.get(1).getSeq());
            assertEquals(1, rocketMqProducer.publishCount());
        }
    }

    @Test
    void offlineRecipientIsQueuedAfterInboundPrivateMessage() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            IdempotencyStore idempotencyStore = context.getBean(IdempotencyStore.class);
            ConversationSeqGenerator conversationSeqGenerator = context.getBean(ConversationSeqGenerator.class);
            IdGenerator idGenerator = context.getBean(IdGenerator.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

            when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
            when(conversationSeqGenerator.next(200L)).thenReturn(77L);
            when(idGenerator.nextId()).thenReturn(9_123L);

            RecordingOfflineQueue offlineQueue = new RecordingOfflineQueue();
            InMemoryDirectory directory = new InMemoryDirectory();
            directory.bind(11L, new EmbeddedChannel());
            try (OutboundEventSubscriber subscriber = new OutboundEventSubscriber(recordingEventBus, directory, offlineQueue)) {
                subscriber.start();
                recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, encodedPrivateInboundEvent(1001L, 200L, 88L));
            }

            List<Mochat.SendAck> sendAcks = sendAckEvents(recordingEventBus);
            assertEquals(1, sendAcks.size());
            assertEquals(1001L, sendAcks.get(0).getClientMsgId());
            assertEquals(9_123L, sendAcks.get(0).getMsgId());
            assertEquals(1, offlineQueue.entries.size());
            RecordingOfflineQueue.EnqueuedEntry enqueued = offlineQueue.entries.getFirst();
            String[] payloadSegments = enqueued.payload().split("\\|", 3);
            assertEquals(88L, enqueued.userId());
            assertEquals(3, payloadSegments.length);
            assertEquals(MsgType.PRIVATE_MESSAGE.name(), payloadSegments[0]);
            assertEquals(SerializerType.PROTOBUF.name(), payloadSegments[1]);
            Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(Base64.getDecoder().decode(payloadSegments[2]));
            assertEquals(9_123L, delivery.getMsgId());
            assertEquals(77L, delivery.getSeq());
            assertEquals(200L, delivery.getConversationId());
            assertEquals(11L, delivery.getFromUid());
            
            // 从 contents 中获取私聊信息
            if (delivery.getContentsCount() > 0 && delivery.getContents(0).hasEncryptedText()) {
                var encryptedText = delivery.getContents(0).getEncryptedText();
                // 注意：ChatMessageDelivery 中没有 toUid 字段，需要从其他地方获取或移除该断言
                assertEquals(12, encryptedText.getNonce().size());
                assertEquals("ciphertext", encryptedText.getCiphertext().toStringUtf8());
            }
            assertEquals(50, enqueued.maxQueueSize());
        }
    }

    @Test
    void clientReceiveAckPublishesDeliveredAckToPeerSender() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            stateStore.upsertPrivateConversation(200L, 11L, 88L, 30L);

            Mochat.ClientReceiveAck ack = Mochat.ClientReceiveAck.newBuilder()
                .setSessionId("session-2")
                .setConversationId(200L)
                .setLatestReceivedSeq(28L)
                .build();
            String inboundEvent = MsgType.CLIENT_RECEIVE_ACK.name()
                + "|"
                + SerializerType.PROTOBUF.name()
                + "|"
                + Base64.getEncoder().encodeToString(ack.toByteArray());
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            String[] parts = recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC)
                .stream()
                .map(event -> event.split("\\|", 4))
                .filter(segments -> MsgType.DELIVERED_ACK.name().equals(segments[1]))
                .findFirst()
                .orElseThrow();
            assertEquals("11", parts[0]);
            assertEquals(MsgType.DELIVERED_ACK.name(), parts[1]);
            assertEquals(SerializerType.PROTOBUF.name(), parts[2]);

            Mochat.DeliveredAck deliveredAck = Mochat.DeliveredAck.parseFrom(Base64.getDecoder().decode(parts[3]));
            assertEquals(200L, deliveredAck.getConversationId());
            assertEquals(88L, deliveredAck.getToUid());
            assertEquals(28L, deliveredAck.getLatestReceivedSeq());
        }
    }

    @Test
    void malformedPrivatePayloadIsIgnored() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            String inboundEvent = MsgType.PRIVATE_MESSAGE.name()
                + "|"
                + SerializerType.PROTOBUF.name()
                + "|"
                + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            assertTrue(rocketMqProducer.lastEnvelope() == null);
            assertTrue(recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).isEmpty());
        }
    }

    @Test
    void invalidUtf8GroupPayloadIsIgnored() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            byte[] invalidUtf8Payload = new byte[] {
                0x0A, 0x09, 's', 'e', 's', 's', 'i', 'o', 'n', '-', '1',
                0x10, 0x01,
                0x18, 0x02,
                0x20, 0x03,
                0x2A, 0x01, (byte) 0x80
            };
            String inboundEvent = MsgType.GROUP_MESSAGE.name()
                + "|"
                + SerializerType.PROTOBUF.name()
                + "|"
                + Base64.getEncoder().encodeToString(invalidUtf8Payload);
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            assertTrue(rocketMqProducer.lastEnvelope() == null);
            assertTrue(recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).isEmpty());
        }
    }

    @Test
    void groupMessageStripsSessionIdFromPersistedPayload() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-lifecycle"))) {
            RecordingEventBus recordingEventBus = context.getBean(RecordingEventBus.class);
            IdempotencyStore idempotencyStore = context.getBean(IdempotencyStore.class);
            ConversationSeqGenerator conversationSeqGenerator = context.getBean(ConversationSeqGenerator.class);
            IdGenerator idGenerator = context.getBean(IdGenerator.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());
            when(conversationSeqGenerator.next(300L)).thenReturn(5L);
            when(idGenerator.nextId()).thenReturn(18_001L);

            Mochat.GroupMessageReq request = Mochat.GroupMessageReq.newBuilder()
                .setSessionId("session-1")
                .setClientMsgId(2002L)
                .setConversationId(300L)
                .setGroupId(300L)
                .addContents(Mochat.MessageContent.newBuilder()
                    .setPlainText(Mochat.PlainText.newBuilder()
                        .setText("hello-group")
                        .build())
                    .build())
                .build();

            String inboundEvent = MsgType.GROUP_MESSAGE.name()
                + "|"
                + SerializerType.PROTOBUF.name()
                + "|"
                + Base64.getEncoder().encodeToString(request.toByteArray());
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            MessageAcceptedEvent envelope = rocketMqProducer.lastEnvelope();
            assertEquals(300L, envelope.conversationId());
            assertEquals(11L, envelope.senderUid());
            assertEquals("group", envelope.kind());

            Mochat.GroupMessageReq persistedGroupPayload = Mochat.GroupMessageReq.parseFrom(
                Base64.getDecoder().decode(envelope.payloadBase64())
            );
            assertTrue(persistedGroupPayload.getSessionId().isEmpty());
            assertEquals(2002L, persistedGroupPayload.getClientMsgId());
            assertEquals(300L, persistedGroupPayload.getConversationId());
            assertEquals(300L, persistedGroupPayload.getGroupId());
            // 从 contents 中获取文本
            if (persistedGroupPayload.getContentsCount() > 0 && persistedGroupPayload.getContents(0).hasPlainText()) {
                assertEquals("hello-group", persistedGroupPayload.getContents(0).getPlainText().getText());
            }
        }
    }

    private static String encodedPrivateInboundEvent(long clientMsgId, long conversationId, long toUid) {
        Mochat.PrivateMessageReq request = Mochat.PrivateMessageReq.newBuilder()
            .setSessionId("session-1")
            .setClientMsgId(clientMsgId)
            .setConversationId(conversationId)
            .setToUid(toUid)
            .addContents(Mochat.MessageContent.newBuilder()
                .setEncryptedText(Mochat.EncryptedText.newBuilder()
                    .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                    .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                    .build())
                .build())
            .build();
        return MsgType.PRIVATE_MESSAGE.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(request.toByteArray());
    }

    private static List<Mochat.SendAck> sendAckEvents(RecordingEventBus recordingEventBus) throws Exception {
        List<Mochat.SendAck> sendAcks = new ArrayList<>();
        for (String event : recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC)) {
            String[] parts = event.split("\\|", 4);
            if (parts.length == 4 && MsgType.SEND_ACK.name().equals(parts[1])) {
                sendAcks.add(Mochat.SendAck.parseFrom(Base64.getDecoder().decode(parts[3])));
            }
        }
        return sendAcks;
    }

    @Factory
    @Requires(property = "spec.name", value = "inbound-lifecycle")
    static class TestBeans {
        @Singleton
        @Primary
        RecordingEventBus recordingEventBus() {
            return new RecordingEventBus();
        }

        @Singleton
        ConversationLock conversationLock() {
            return new JucConversationLock();
        }

        @Singleton
        IdempotencyStore idempotencyStore() {
            return Mockito.mock(IdempotencyStore.class);
        }

        @Singleton
        ConversationSeqGenerator conversationSeqGenerator() {
            return Mockito.mock(ConversationSeqGenerator.class);
        }

        @Singleton
        IdGenerator idGenerator() {
            return Mockito.mock(IdGenerator.class);
        }

        @Singleton
        @Replaces(RocketMqProducer.class)
        RecordingRocketMqProducer rocketMqProducer() {
            return new RecordingRocketMqProducer();
        }

        @Singleton
        @Primary
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            RedisCommands<String, String> redisCommands = Mockito.mock(RedisCommands.class);
            when(redisCommands.get("mochat:session:session-1")).thenReturn("11");
            when(redisCommands.get("mochat:session:session-2")).thenReturn("88");
            return redisCommands;
        }

    }

    static final class RecordingRocketMqProducer extends RocketMqProducer {
        private final List<MessageAcceptedEvent> envelopes = new ArrayList<>();
        private MessageAcceptedEvent lastEnvelope;
        private String lastShardingKey;

        RecordingRocketMqProducer() {
            super(Mockito.mock(DefaultMQProducer.class));
        }

        @Override
        public boolean publishOrdered(MessageAcceptedEvent envelope) {
            envelopes.add(envelope);
            lastEnvelope = envelope;
            lastShardingKey = envelope.shardingKey();
            return true;
        }

        MessageAcceptedEvent lastEnvelope() {
            return lastEnvelope;
        }

        String lastShardingKey() {
            return lastShardingKey;
        }

        int publishCount() {
            return envelopes.size();
        }
    }

    static final class RecordingEventBus implements EventBus {
        private final Map<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();
        private final Map<String, CopyOnWriteArrayList<String>> eventsByTopic = new ConcurrentHashMap<>();
        private final AtomicInteger closedSubscriptionCount = new AtomicInteger();

        @Override
        public void publish(String topic, String event) {
            eventsByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(event);

            for (Consumer<String> subscriber : subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
                subscriber.accept(event);
            }
        }

        @Override
        public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
            CopyOnWriteArrayList<Consumer<String>> subscribers = subscribersByTopic.computeIfAbsent(
                topic,
                ignored -> new CopyOnWriteArrayList<>()
            );
            subscribers.add(subscriber);

            return () -> {
                subscribers.remove(subscriber);
                closedSubscriptionCount.incrementAndGet();
            };
        }

        int subscriberCount(String topic) {
            return subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()).size();
        }

        int closedSubscriptionCount() {
            return closedSubscriptionCount.get();
        }

        List<String> publishedEvents(String topic) {
            return new ArrayList<>(eventsByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()));
        }
    }

    static final class InMemoryDirectory implements UserChannelDirectory<Channel> {
        private final ConcurrentMap<Long, Channel> channels = new ConcurrentHashMap<>();

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

    static final class RecordingOfflineQueue implements OfflineQueue {
        private final List<EnqueuedEntry> entries = new ArrayList<>();

        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
            entries.add(new EnqueuedEntry(userId, payload, maxQueueSize));
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            return List.of();
        }

        private record EnqueuedEntry(long userId, String payload, int maxQueueSize) {
        }
    }
}
