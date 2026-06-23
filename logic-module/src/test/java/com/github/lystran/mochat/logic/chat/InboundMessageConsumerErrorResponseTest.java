package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.protocol.ErrorCode;
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

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class InboundMessageConsumerErrorResponseTest {
    @Test
    void routedPrivateInvalidSessionEmitsErrorResponseWithoutIngest() throws Exception {
        RecordingEventBus eventBus = new RecordingEventBus();
        MessageIngestService messageIngestService = Mockito.mock(MessageIngestService.class);
        ReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();

        try (InboundMessageConsumer consumer = new InboundMessageConsumer(
            eventBus,
            invalidSessionService(),
            messageIngestService,
            new ReceiptService(stateStore, eventBus)
        )) {
            consumer.start();

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, routedInboundEvent(11L, MsgType.PRIVATE_MESSAGE, invalidPrivateBody()));

            Mochat.ErrorResponse error = assertSingleErrorResponse(eventBus, 11L);
            assertEquals(ErrorCode.SESSION_INVALID.code(), error.getErrorCode());
            Mockito.verifyNoInteractions(messageIngestService);
        }
    }

    @Test
    void routedGroupInvalidSessionEmitsErrorResponseWithoutIngest() throws Exception {
        RecordingEventBus eventBus = new RecordingEventBus();
        MessageIngestService messageIngestService = Mockito.mock(MessageIngestService.class);
        ReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();

        try (InboundMessageConsumer consumer = new InboundMessageConsumer(
            eventBus,
            invalidSessionService(),
            messageIngestService,
            new ReceiptService(stateStore, eventBus)
        )) {
            consumer.start();

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, routedInboundEvent(11L, MsgType.GROUP_MESSAGE, invalidGroupBody()));

            Mochat.ErrorResponse error = assertSingleErrorResponse(eventBus, 11L);
            assertEquals(ErrorCode.SESSION_INVALID.code(), error.getErrorCode());
            Mockito.verifyNoInteractions(messageIngestService);
        }
    }

    @Test
    void routedReceiptInvalidSessionEmitsErrorResponseWithoutReceiptHandling() throws Exception {
        RecordingEventBus eventBus = new RecordingEventBus();
        MessageIngestService messageIngestService = Mockito.mock(MessageIngestService.class);
        ReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();
        stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        try (InboundMessageConsumer consumer = new InboundMessageConsumer(
            eventBus,
            invalidSessionService(),
            messageIngestService,
            new ReceiptService(stateStore, eventBus)
        )) {
            consumer.start();

            eventBus.publish(
                InboundMessageConsumer.DEFAULT_INBOUND_TOPIC,
                routedInboundEvent(11L, MsgType.CLIENT_RECEIVE_ACK, invalidReceiptBody(200L, 66L))
            );

            Mochat.ErrorResponse error = assertSingleErrorResponse(eventBus, 11L);
            assertEquals(ErrorCode.SESSION_INVALID.code(), error.getErrorCode());
            assertEquals(
                new ReceiptConversationStateStore.PrivateConversationState(200L, 11L, 88L, 70L, 0L, 0L),
                stateStore.findPrivateConversation(200L).orElseThrow()
            );
            Mockito.verifyNoInteractions(messageIngestService);
        }
    }

    @Test
    void privateNotFriendEmitsErrorResponse() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-error-responses"))) {
            RecordingEventBus eventBus = context.getBean(RecordingEventBus.class);
            MessageRelationshipRepository relationshipRepository = context.getBean(MessageRelationshipRepository.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);
            when(relationshipRepository.privateMessageState(200L, 11L, 88L))
                .thenReturn(MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND);

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, privateInboundEvent());

            String[] parts = eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).getFirst().split("\\|", 4);
            assertEquals("11", parts[0]);
            assertEquals(MsgType.ERROR_RESPONSE.name(), parts[1]);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(Base64.getDecoder().decode(parts[3]));
            assertEquals(1300, error.getErrorCode());
            assertEquals("private message requires active friendship", error.getMessage());
            assertNull(rocketMqProducer.lastEnvelope());
        }
    }

    @Test
    void privateBlockedEmitsErrorResponse() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-error-responses"))) {
            RecordingEventBus eventBus = context.getBean(RecordingEventBus.class);
            MessageRelationshipRepository relationshipRepository = context.getBean(MessageRelationshipRepository.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);
            when(relationshipRepository.privateMessageState(200L, 11L, 88L))
                .thenReturn(MessageRelationshipRepository.PrivateMessageState.BLOCKED);

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, privateInboundEvent());

            String[] parts = eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).getFirst().split("\\|", 4);
            assertEquals("11", parts[0]);
            assertEquals(MsgType.ERROR_RESPONSE.name(), parts[1]);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(Base64.getDecoder().decode(parts[3]));
            assertEquals(1301, error.getErrorCode());
            assertEquals("friendship is blocked", error.getMessage());
            assertNull(rocketMqProducer.lastEnvelope());
        }
    }

    @Test
    void orderedPublishFailureEmitsMqPublishFailedErrorResponseWithoutSendAck() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-error-responses"))) {
            RecordingEventBus eventBus = context.getBean(RecordingEventBus.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);
            rocketMqProducer.setPublishResult(false);

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, privateInboundEvent());

            Mochat.ErrorResponse error = assertSingleErrorResponse(eventBus, 11L);
            assertEquals(ErrorCode.MQ_PUBLISH_FAILED.code(), error.getErrorCode());
            assertEquals(0L, eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).stream()
                .filter(event -> event.contains("|" + MsgType.SEND_ACK.name() + "|"))
                .count());
        }
    }

    @Test
    void orderedPublishThrowEmitsMqPublishFailedErrorResponseWithoutSendAck() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-error-responses"))) {
            RecordingEventBus eventBus = context.getBean(RecordingEventBus.class);
            ReceiptConversationStateStore stateStore = context.getBean(ReceiptConversationStateStore.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            stateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);
            rocketMqProducer.setPublishException(new IllegalStateException("Ordered publish failed", new RuntimeException("mq timeout")));

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, privateInboundEvent());

            Mochat.ErrorResponse error = assertSingleErrorResponse(eventBus, 11L);
            assertEquals(ErrorCode.MQ_PUBLISH_FAILED.code(), error.getErrorCode());
            assertEquals(0L, eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).stream()
                .filter(event -> event.contains("|" + MsgType.SEND_ACK.name() + "|"))
                .count());
        }
    }

    @Test
    void groupNotInMemberEmitsErrorResponse() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "inbound-error-responses"))) {
            RecordingEventBus eventBus = context.getBean(RecordingEventBus.class);
            MessageRelationshipRepository relationshipRepository = context.getBean(MessageRelationshipRepository.class);
            RecordingRocketMqProducer rocketMqProducer = context.getBean(RecordingRocketMqProducer.class);

            when(relationshipRepository.isActiveGroupMember(300L, 11L)).thenReturn(false);

            eventBus.publish(InboundMessageConsumer.DEFAULT_INBOUND_TOPIC, groupInboundEvent());

            String[] parts = eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC).getFirst().split("\\|", 4);
            assertEquals("11", parts[0]);
            assertEquals(MsgType.ERROR_RESPONSE.name(), parts[1]);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(Base64.getDecoder().decode(parts[3]));
            assertEquals(1400, error.getErrorCode());
            assertEquals("sender is not an active group member", error.getMessage());
            assertNull(rocketMqProducer.lastEnvelope());
        }
    }

    private static String privateInboundEvent() {
        Mochat.PrivateMessageReq request = Mochat.PrivateMessageReq.newBuilder()
            .setSessionId("session-1")
            .setClientMsgId(1001L)
            .setConversationId(200L)
            .setToUid(88L)
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

    private static SessionService invalidSessionService() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = Mockito.mock(RedisCommands.class);
        when(redisCommands.get(Mockito.anyString())).thenReturn(null);
        return new SessionService(redisCommands);
    }

    private static String routedInboundEvent(long routingUserId, MsgType msgType, byte[] body) {
        return routingUserId
            + "|"
            + msgType.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(body);
    }

    private static byte[] invalidPrivateBody() {
        return Mochat.PrivateMessageReq.newBuilder()
            .setSessionId("invalid-session")
            .setClientMsgId(3001L)
            .setConversationId(200L)
            .setToUid(88L)
            .addContents(Mochat.MessageContent.newBuilder()
                .setEncryptedText(Mochat.EncryptedText.newBuilder()
                    .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                    .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                    .build())
                .build())
            .build()
            .toByteArray();
    }

    private static byte[] invalidGroupBody() {
        return Mochat.GroupMessageReq.newBuilder()
            .setSessionId("invalid-session")
            .setClientMsgId(3002L)
            .setConversationId(300L)
            .setGroupId(300L)
            .addContents(Mochat.MessageContent.newBuilder()
                .setPlainText(Mochat.PlainText.newBuilder()
                    .setText("hello-group")
                    .build())
                .build())
            .build()
            .toByteArray();
    }

    private static byte[] invalidReceiptBody(long conversationId, long latestReceivedSeq) {
        return Mochat.ClientReceiveAck.newBuilder()
            .setSessionId("invalid-session")
            .setConversationId(conversationId)
            .setLatestReceivedSeq(latestReceivedSeq)
            .build()
            .toByteArray();
    }

    private static Mochat.ErrorResponse assertSingleErrorResponse(RecordingEventBus eventBus, long userId) throws Exception {
        var outboundEvents = eventBus.publishedEvents(MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
        assertEquals(1, outboundEvents.size());
        String[] parts = outboundEvents.getFirst().split("\\|", 4);
        assertEquals(Long.toString(userId), parts[0]);
        assertEquals(MsgType.ERROR_RESPONSE.name(), parts[1]);
        return Mochat.ErrorResponse.parseFrom(Base64.getDecoder().decode(parts[3]));
    }

    private static String groupInboundEvent() {
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
        return MsgType.GROUP_MESSAGE.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(request.toByteArray());
    }

    @Factory
    @Requires(property = "spec.name", value = "inbound-error-responses")
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

        @Singleton
        @Primary
        MessageRelationshipRepository messageRelationshipRepository() {
            return Mockito.mock(MessageRelationshipRepository.class);
        }

        @Singleton
        @Primary
        ReceiptConversationStateStore receiptConversationStateStore() {
            return new InMemoryReceiptConversationStateStore();
        }
    }

    static final class RecordingRocketMqProducer extends RocketMqProducer {
        private MessageAcceptedEvent lastEnvelope;
        private boolean publishResult = true;
        private IllegalStateException publishException;

        RecordingRocketMqProducer() {
            super(Mockito.mock(DefaultMQProducer.class));
        }

        @Override
        public boolean publishOrdered(MessageAcceptedEvent envelope) {
            this.lastEnvelope = envelope;
            if (publishException != null) {
                throw publishException;
            }
            return publishResult;
        }

        void setPublishResult(boolean publishResult) {
            this.publishResult = publishResult;
            this.publishException = null;
        }

        void setPublishException(IllegalStateException publishException) {
            this.publishException = publishException;
            this.publishResult = true;
        }

        MessageAcceptedEvent lastEnvelope() {
            return lastEnvelope;
        }
    }

    static final class RecordingEventBus implements EventBus {
        private final Map<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();
        private final Map<String, CopyOnWriteArrayList<String>> eventsByTopic = new ConcurrentHashMap<>();
        private final AtomicInteger closedSubscriptionCount = new AtomicInteger();

        @Override
        public void publish(String topic, String payload) {
            eventsByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(payload);
            subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()).forEach(subscriber -> subscriber.accept(payload));
        }

        @Override
        public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
            subscribersByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
            return () -> {
                subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(subscriber);
                closedSubscriptionCount.incrementAndGet();
            };
        }

        CopyOnWriteArrayList<String> publishedEvents(String topic) {
            return eventsByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>());
        }
    }
}
