package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
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

            assertEquals(1, recordingEventBus.subscriberCount(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC));

            when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
            when(conversationSeqGenerator.next(200L)).thenReturn(77L);
            when(idGenerator.nextId()).thenReturn(9_123L);

            Mochat.PrivateMessageReq request = Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-1")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build();

            String inboundEvent = MsgType.PRIVATE_MESSAGE.name()
                + "|"
                + SerializerType.PROTOBUF.name()
                + "|"
                + Base64.getEncoder().encodeToString(request.toByteArray());
            recordingEventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, inboundEvent);

            MessageIngestEnvelope envelope = rocketMqProducer.lastEnvelope();
            assertEquals("200", rocketMqProducer.lastShardingKey());
            verify(idempotencyStore).storeIfAbsent(11L, 1001L, 9_123L, 77L);

            assertEquals(200L, envelope.conversationId());
            assertEquals(1001L, envelope.clientMsgId());
            assertEquals(11L, envelope.senderUid());
            assertEquals(MessageIngestRequest.KIND_PRIVATE, envelope.kind());

            List<String> outboundEvents = recordingEventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
            assertEquals(2, outboundEvents.size());

            String[] parts = outboundEvents
                .stream()
                .map(event -> event.split("\\|", 4))
                .filter(segments -> MsgType.SEND_ACK.name().equals(segments[1]))
                .findFirst()
                .orElseThrow();
            assertEquals("11", parts[0]);
            assertEquals(MsgType.SEND_ACK.name(), parts[1]);
            assertEquals(SerializerType.PROTOBUF.name(), parts[2]);

            Mochat.SendAck sendAck = Mochat.SendAck.parseFrom(Base64.getDecoder().decode(parts[3]));
            assertEquals(1001L, sendAck.getClientMsgId());
            assertEquals(9_123L, sendAck.getMsgId());
            assertEquals(77L, sendAck.getSeq());
            assertTrue(sendAck.getServerTimeMs() > 0);
        }

        assertEquals(0, recordingEventBus.subscriberCount(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC));
        assertEquals(1, recordingEventBus.closedSubscriptionCount());
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
            return redisCommands;
        }

    }

    static final class RecordingRocketMqProducer extends RocketMqProducer {
        private MessageIngestEnvelope lastEnvelope;
        private String lastShardingKey;

        RecordingRocketMqProducer() {
            super(Mockito.mock(DefaultMQProducer.class));
        }

        @Override
        public boolean publishOrdered(MessageIngestEnvelope envelope, String shardingKey) {
            lastEnvelope = envelope;
            lastShardingKey = shardingKey;
            return true;
        }

        MessageIngestEnvelope lastEnvelope() {
            return lastEnvelope;
        }

        String lastShardingKey() {
            return lastShardingKey;
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
}
