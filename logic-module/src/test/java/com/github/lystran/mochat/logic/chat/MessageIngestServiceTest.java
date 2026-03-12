package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageIngestServiceTest {
    @Test
    void successfulPrivateIngestPublishesDeliveryEventToRecipient() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        Mochat.PrivateMessageReq privateMessageReq = Mochat.PrivateMessageReq.newBuilder()
            .setSessionId("session-1")
            .setClientMsgId(1001L)
            .setConversationId(200L)
            .setToUid(88L)
            .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
            .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
            .build();

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            Base64.getEncoder().encodeToString(privateMessageReq.toByteArray())
        ));

        ArgumentCaptor<String> outboundEventCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(2)).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), outboundEventCaptor.capture());

        String recipientDeliveryEvent = outboundEventCaptor.getAllValues().get(1);
        String[] deliverySegments = recipientDeliveryEvent.split("\\|", 4);
        assertEquals("88", deliverySegments[0]);
        assertEquals(MsgType.PRIVATE_MESSAGE.name(), deliverySegments[1]);
        assertEquals(SerializerType.PROTOBUF.name(), deliverySegments[2]);

        Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(
            Base64.getDecoder().decode(deliverySegments[3])
        );
        assertEquals(9_123L, delivery.getMsgId());
        assertEquals(77L, delivery.getSeq());
        assertEquals(200L, delivery.getConversationId());
        assertEquals(11L, delivery.getFromUid());
        assertEquals(88L, delivery.getPrivatePayload().getToUid());
    }

    @Test
    void duplicateSenderAndClientMsgIdReturnsSameMsgIdAndSeq() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(new IdempotencyStore.StoredSendResult(8_001L, 1L)));
        when(seqGenerator.next(200L)).thenReturn(1L);
        when(idGenerator.nextId()).thenReturn(8_001L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(123_456L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        MessageIngestRequest request = MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            encodedPrivateRequest(1001L, 200L, 88L)
        );

        MessageIngestResult first = service.ingest(request);
        MessageIngestResult duplicate = service.ingest(request);

        assertEquals(first.msgId(), duplicate.msgId());
        assertEquals(first.seq(), duplicate.seq());
        verify(seqGenerator, times(1)).next(200L);
        verify(rocketMqProducer, times(1)).publishOrdered(any(MessageAcceptedEvent.class));
    }

    @Test
    void seqStrictlyIncreasesWithinConversation() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(eq(11L), any(Long.class))).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(10L, 11L);
        when(idGenerator.nextId()).thenReturn(9_001L, 9_002L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(456_789L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        MessageIngestResult first = service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            encodedPrivateRequest(1001L, 200L, 88L)
        ));
        MessageIngestResult second = service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1002L,
            11L,
            88L,
            encodedPrivateRequest(1002L, 200L, 88L)
        ));

        assertTrue(second.seq() > first.seq());
        ArgumentCaptor<MessageAcceptedEvent> envelopeCaptor = ArgumentCaptor.forClass(MessageAcceptedEvent.class);
        verify(rocketMqProducer, times(2)).publishOrdered(envelopeCaptor.capture());
        assertEquals(10L, envelopeCaptor.getAllValues().get(0).seq());
        assertEquals(11L, envelopeCaptor.getAllValues().get(1).seq());
    }

    @Test
    void emitsSendAckAfterPublishSuccessWithClientMsgIdMsgIdAndSeq() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            encodedPrivateRequest(1001L, 200L, 88L)
        ));

        InOrder inOrder = inOrder(rocketMqProducer, idempotencyStore, eventBus);
        inOrder.verify(rocketMqProducer).publishOrdered(any(MessageAcceptedEvent.class));
        inOrder.verify(idempotencyStore).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        ArgumentCaptor<String> outboundEventCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(2)).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), outboundEventCaptor.capture());

        String[] parts = outboundEventCaptor
            .getAllValues()
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
    }

    @Test
    void doesNotEmitAckWhenOrderedPublishFails() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(false);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        MessageRejectException rejection = assertThrows(
            MessageRejectException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );
        assertEquals(ErrorCode.MQ_PUBLISH_FAILED, rejection.errorCode());
        assertEquals(ErrorCode.MQ_PUBLISH_FAILED.code(), rejection.errorCode().code());

        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
        assertEquals(70L, receiptStateStore.findPrivateConversation(200L).orElseThrow().latestSeq());
    }

    @Test
    void doesNotEmitAckWhenOrderedPublishThrows() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class)))
            .thenThrow(new IllegalStateException("Ordered publish failed", new RuntimeException("mq timeout")));

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        MessageRejectException rejection = assertThrows(
            MessageRejectException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );
        assertEquals(ErrorCode.MQ_PUBLISH_FAILED, rejection.errorCode());
        assertEquals(ErrorCode.MQ_PUBLISH_FAILED.code(), rejection.errorCode().code());

        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
        assertEquals(70L, receiptStateStore.findPrivateConversation(200L).orElseThrow().latestSeq());
    }

    @Test
    void invalidPrivateParticipantsFailBeforePublishAndOutboundSideEffects() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 88L, 11L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void privateConversationMustExistBeforePublish() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void privateConversationParticipantsMustMatchConversationState() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 99L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void privateMessageRequiresExactlyTwelveByteNonce() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        String invalidPayload = Base64.getEncoder().encodeToString(
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-test")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[11]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, invalidPayload))
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(1001L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void privateMessageRequiresCiphertext() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        String invalidPayload = Base64.getEncoder().encodeToString(
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-test")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .build()
                .toByteArray()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, invalidPayload))
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(1001L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void blockedFriendshipRejectsPrivateMessageBeforePublish() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);
        InMemoryReceiptConversationStateStore receiptStateStore = new InMemoryReceiptConversationStateStore();
        receiptStateStore.upsertPrivateConversation(200L, 11L, 88L, 70L);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            receiptStateStore,
            new StubMessageRelationshipRepository(
                MessageRelationshipRepository.PrivateMessageState.BLOCKED,
                false,
                List.of()
            ),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(
                MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest(1001L, 200L, 88L))
            )
        );

        assertTrue(exception.getMessage().contains("blocked"));
        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(1001L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void groupMessageRequiresActiveMembershipBeforePublish() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            new InMemoryReceiptConversationStateStore(),
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.ACTIVE, false, List.of(88L, 99L)),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest(2002L, 300L, 300L, "hello")))
        );

        assertTrue(exception.getMessage().contains("group"));
        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(2002L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void successfulGroupIngestPublishesDeliveryEventToEveryOtherActiveMember() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());
        when(seqGenerator.next(300L)).thenReturn(8L);
        when(idGenerator.nextId()).thenReturn(9_999L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus,
            new InMemoryReceiptConversationStateStore(),
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.ACTIVE, true, List.of(11L, 88L, 99L)),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest(2002L, 300L, 300L, "hello group")));

        ArgumentCaptor<String> outboundEventCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(3)).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), outboundEventCaptor.capture());

        List<String[]> deliverySegments = outboundEventCaptor.getAllValues()
            .stream()
            .map(event -> event.split("\\|", 4))
            .filter(segments -> MsgType.GROUP_MESSAGE.name().equals(segments[1]))
            .toList();

        assertEquals(2, deliverySegments.size());
        assertTrue(deliverySegments.stream().anyMatch(segments -> "88".equals(segments[0])));
        assertTrue(deliverySegments.stream().anyMatch(segments -> "99".equals(segments[0])));

        for (String[] segments : deliverySegments) {
            assertEquals(SerializerType.PROTOBUF.name(), segments[2]);
            Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(Base64.getDecoder().decode(segments[3]));
            assertEquals(9_999L, delivery.getMsgId());
            assertEquals(8L, delivery.getSeq());
            assertEquals(300L, delivery.getConversationId());
            assertEquals(11L, delivery.getFromUid());
            assertEquals(300L, delivery.getGroupPayload().getGroupId());
            assertEquals("hello group", delivery.getGroupPayload().getText());
        }
    }

    @Test
    void privateRetryAfterRouteFailureDoesNotQueueOfflineWhenRetrySucceeds() {
        for (MessageDeliveryStatus firstAttemptStatus : List.of(
            MessageDeliveryStatus.ROUTE_STALE,
            MessageDeliveryStatus.USER_OFFLINE,
            MessageDeliveryStatus.WRITE_FAILED
        )) {
            IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
            ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
            IdGenerator idGenerator = mock(IdGenerator.class);
            RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
            MessageSendPolicyGateway messageSendPolicyGateway = mock(MessageSendPolicyGateway.class);
            MessageRecipientDispatcher dispatcher = mock(MessageRecipientDispatcher.class);
            OfflineQueue offlineQueue = mock(OfflineQueue.class);

            when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
            when(seqGenerator.next(200L)).thenReturn(77L);
            when(idGenerator.nextId()).thenReturn(9_123L);
            when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
            when(dispatcher.dispatchPrivate(any(PrivateMessageDelivery.class)))
                .thenReturn(firstAttemptStatus)
                .thenReturn(MessageDeliveryStatus.DELIVERED);

            MessageIngestService service = directMessageService(
                idempotencyStore,
                seqGenerator,
                idGenerator,
                rocketMqProducer,
                messageSendPolicyGateway,
                dispatcher,
                offlineQueue
            );

            service.ingest(MessageIngestRequest.privateMessage(
                11L,
                200L,
                1001L,
                11L,
                88L,
                encodedPrivateRequest(1001L, 200L, 88L)
            ));

            verify(dispatcher, times(2)).dispatchPrivate(any(PrivateMessageDelivery.class));
            verifyNoInteractions(offlineQueue);
        }
    }

    @Test
    void privateRetryFailureQueuesReplayableOfflineEnvelopeOnce() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        MessageSendPolicyGateway messageSendPolicyGateway = mock(MessageSendPolicyGateway.class);
        MessageRecipientDispatcher dispatcher = mock(MessageRecipientDispatcher.class);
        OfflineQueue offlineQueue = mock(OfflineQueue.class);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
        when(dispatcher.dispatchPrivate(any(PrivateMessageDelivery.class)))
            .thenReturn(MessageDeliveryStatus.ROUTE_STALE)
            .thenReturn(MessageDeliveryStatus.USER_OFFLINE);

        MessageIngestService service = directMessageService(
            idempotencyStore,
            seqGenerator,
            idGenerator,
            rocketMqProducer,
            messageSendPolicyGateway,
            dispatcher,
            offlineQueue
        );

        service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            encodedPrivateRequest(1001L, 200L, 88L)
        ));

        ArgumentCaptor<String> offlinePayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(dispatcher, times(2)).dispatchPrivate(any(PrivateMessageDelivery.class));
        verify(offlineQueue).enqueue(eq(88L), offlinePayloadCaptor.capture(), eq(50));
        verify(offlineQueue, times(1)).enqueue(anyLong(), anyString(), anyInt());

        String[] payloadSegments = offlinePayloadCaptor.getValue().split("\\|", 3);
        assertEquals(MsgType.PRIVATE_MESSAGE.name(), payloadSegments[0]);
        assertEquals(SerializerType.PROTOBUF.name(), payloadSegments[1]);
        Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(
            Base64.getDecoder().decode(payloadSegments[2])
        );
        assertEquals(9_123L, delivery.getMsgId());
        assertEquals(77L, delivery.getSeq());
        assertEquals(200L, delivery.getConversationId());
        assertEquals(11L, delivery.getFromUid());
        assertEquals(88L, delivery.getPrivatePayload().getToUid());
        assertEquals("ciphertext", delivery.getPrivatePayload().getCiphertext().toStringUtf8());
    }

    @Test
    void privateRetryExceptionQueuesOfflineEnvelope() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        MessageSendPolicyGateway messageSendPolicyGateway = mock(MessageSendPolicyGateway.class);
        MessageRecipientDispatcher dispatcher = mock(MessageRecipientDispatcher.class);
        OfflineQueue offlineQueue = mock(OfflineQueue.class);

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
        when(dispatcher.dispatchPrivate(any(PrivateMessageDelivery.class)))
            .thenReturn(MessageDeliveryStatus.WRITE_FAILED)
            .thenThrow(new RuntimeException("gateway rpc unavailable"));

        MessageIngestService service = directMessageService(
            idempotencyStore,
            seqGenerator,
            idGenerator,
            rocketMqProducer,
            messageSendPolicyGateway,
            dispatcher,
            offlineQueue
        );

        service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            encodedPrivateRequest(1001L, 200L, 88L)
        ));

        verify(dispatcher, times(2)).dispatchPrivate(any(PrivateMessageDelivery.class));
        verify(offlineQueue).enqueue(eq(88L), anyString(), eq(50));
    }

    private static MessageIngestService directMessageService(
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator seqGenerator,
        IdGenerator idGenerator,
        RocketMqProducer rocketMqProducer,
        MessageSendPolicyGateway messageSendPolicyGateway,
        MessageRecipientDispatcher dispatcher,
        OfflineQueue offlineQueue
    ) {
        return new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
            rocketMqProducer,
            messageSendPolicyGateway,
            (senderUid, clientMsgId, msgId, seq, serverTimeMs) -> {
            },
            dispatcher,
            (conversationId, peerUidLow, peerUidHigh, seq) -> {
            },
            offlineQueue
        );
    }

    private static String encodedPrivateRequest(long clientMsgId, long conversationId, long toUid) {
        var request = Mochat.PrivateMessageReq.newBuilder()
            .setSessionId("session-test")
            .setClientMsgId(clientMsgId)
            .setConversationId(conversationId)
            .setToUid(toUid)
            .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
            .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
            .build();
        return Base64.getEncoder().encodeToString(request.toByteArray());
    }

    private static String encodedGroupRequest(long clientMsgId, long conversationId, long groupId, String text) {
        var request = Mochat.GroupMessageReq.newBuilder()
            .setSessionId("session-test")
            .setClientMsgId(clientMsgId)
            .setConversationId(conversationId)
            .setGroupId(groupId)
            .setText(text)
            .build();
        return Base64.getEncoder().encodeToString(request.toByteArray());
    }

    private record StubMessageRelationshipRepository(
        MessageRelationshipRepository.PrivateMessageState privateState,
        boolean activeGroupMember,
        List<Long> groupMembers
    ) implements MessageRelationshipRepository {
        @Override
        public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
            return privateState;
        }

        @Override
        public boolean groupExists(long groupId) {
            return true;
        }

        @Override
        public boolean isActiveGroupMember(long groupId, long userId) {
            return activeGroupMember;
        }

        @Override
        public List<Long> listActiveGroupMemberIds(long groupId) {
            return groupMembers;
        }
    }
}
