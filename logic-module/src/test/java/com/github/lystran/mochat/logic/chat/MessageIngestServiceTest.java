package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageIngestServiceTest {
    @Test
    void duplicateSenderAndClientMsgIdReturnsSameMsgIdAndSeq() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(11L, 1001L))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(new IdempotencyStore.StoredSendResult(8_001L, 1L)));
        when(seqGenerator.next(200L)).thenReturn(1L);
        when(idGenerator.nextId()).thenReturn(8_001L);
        when(rocketMqProducer.publishOrdered(any(MessageIngestEnvelope.class), eq("200"))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(123_456L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus
        );

        MessageIngestRequest request = MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            "payload-base64"
        );

        MessageIngestResult first = service.ingest(request);
        MessageIngestResult duplicate = service.ingest(request);

        assertEquals(first.msgId(), duplicate.msgId());
        assertEquals(first.seq(), duplicate.seq());
        verify(seqGenerator, times(1)).next(200L);
        verify(rocketMqProducer, times(1)).publishOrdered(any(MessageIngestEnvelope.class), eq("200"));
    }

    @Test
    void seqStrictlyIncreasesWithinConversation() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(eq(11L), any(Long.class))).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(10L, 11L);
        when(idGenerator.nextId()).thenReturn(9_001L, 9_002L);
        when(rocketMqProducer.publishOrdered(any(MessageIngestEnvelope.class), eq("200"))).thenReturn(true);

        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            Clock.fixed(Instant.ofEpochMilli(456_789L), ZoneOffset.UTC),
            rocketMqProducer,
            eventBus
        );

        MessageIngestResult first = service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            "payload-1"
        ));
        MessageIngestResult second = service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1002L,
            11L,
            88L,
            "payload-2"
        ));

        assertTrue(second.seq() > first.seq());
        ArgumentCaptor<MessageIngestEnvelope> envelopeCaptor = ArgumentCaptor.forClass(MessageIngestEnvelope.class);
        verify(rocketMqProducer, times(2)).publishOrdered(envelopeCaptor.capture(), eq("200"));
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

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageIngestEnvelope.class), eq("200"))).thenReturn(true);

        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        MessageIngestService service = new MessageIngestService(
            new JucConversationLock(),
            idempotencyStore,
            seqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            eventBus
        );

        service.ingest(MessageIngestRequest.privateMessage(
            11L,
            200L,
            1001L,
            11L,
            88L,
            "payload-1"
        ));

        InOrder inOrder = inOrder(rocketMqProducer, idempotencyStore, eventBus);
        inOrder.verify(rocketMqProducer).publishOrdered(any(MessageIngestEnvelope.class), eq("200"));
        inOrder.verify(idempotencyStore).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        ArgumentCaptor<String> outboundEventCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(eventBus).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), outboundEventCaptor.capture());

        String[] parts = outboundEventCaptor.getValue().split("\\|", 4);
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

        when(idempotencyStore.find(11L, 1001L)).thenReturn(Optional.empty());
        when(seqGenerator.next(200L)).thenReturn(77L);
        when(idGenerator.nextId()).thenReturn(9_123L);
        when(rocketMqProducer.publishOrdered(any(MessageIngestEnvelope.class), eq("200"))).thenReturn(false);

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
            IllegalStateException.class,
            () -> service.ingest(MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, "payload-1"))
        );

        verify(idempotencyStore, never()).storeIfAbsent(11L, 1001L, 9_123L, 77L);
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }
}
