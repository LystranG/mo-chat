package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageIngestServiceRelationshipAndGroupTest {
    @Test
    void rejectsPrivateMessageWhenFriendshipIsMissing() {
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
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND, false, List.of()),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest()))
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(1001L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void rejectsPrivateMessageWhenFriendshipIsBlocked() {
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
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.BLOCKED, false, List.of()),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.privateMessage(11L, 200L, 1001L, 11L, 88L, encodedPrivateRequest()))
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(1001L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void rejectsGroupMessageWhenSenderIsNotActiveMember() {
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
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.ACTIVE, false, List.of()),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest()))
        );

        verify(rocketMqProducer, never()).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore, never()).storeIfAbsent(eq(11L), eq(2002L), anyLong(), anyLong());
        verify(eventBus, never()).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), any(String.class));
    }

    @Test
    void fansOutGroupMessageToActiveMembersExceptSender() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        EventBus eventBus = mock(EventBus.class);

        when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());
        when(seqGenerator.next(300L)).thenReturn(7L);
        when(idGenerator.nextId()).thenReturn(9_002L);
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
            new StubMessageRelationshipRepository(MessageRelationshipRepository.PrivateMessageState.ACTIVE, true, List.of(11L, 22L, 33L)),
            MessageIngestService.DEFAULT_OUTBOUND_TOPIC
        );

        service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest()));

        verify(rocketMqProducer).publishOrdered(any(MessageAcceptedEvent.class));
        verify(idempotencyStore).storeIfAbsent(11L, 2002L, 9_002L, 7L);
        ArgumentCaptor<String> outboundEventCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(3)).publish(eq(MessageIngestService.DEFAULT_OUTBOUND_TOPIC), outboundEventCaptor.capture());

        List<String[]> parts = outboundEventCaptor.getAllValues().stream()
            .map(event -> event.split("\\|", 4))
            .toList();

        assertEquals(1L, parts.stream().filter(segments -> "11".equals(segments[0]) && MsgType.SEND_ACK.name().equals(segments[1])).count());
        assertEquals(1L, parts.stream().filter(segments -> "22".equals(segments[0]) && MsgType.GROUP_MESSAGE.name().equals(segments[1])).count());
        assertEquals(1L, parts.stream().filter(segments -> "33".equals(segments[0]) && MsgType.GROUP_MESSAGE.name().equals(segments[1])).count());

        String[] recipientPayload = parts.stream()
            .filter(segments -> "22".equals(segments[0]) && MsgType.GROUP_MESSAGE.name().equals(segments[1]))
            .findFirst()
            .orElseThrow();
        assertEquals(SerializerType.PROTOBUF.name(), recipientPayload[2]);

        Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(Base64.getDecoder().decode(recipientPayload[3]));
        assertEquals(9_002L, delivery.getMsgId());
        assertEquals(7L, delivery.getSeq());
        assertEquals(300L, delivery.getConversationId());
        assertEquals(11L, delivery.getFromUid());
        assertEquals(300L, delivery.getGroupPayload().getGroupId());
        assertEquals("hello-group", delivery.getGroupPayload().getText());
    }

    @Test
    void groupRetryOnlyQueuesRecipientsThatStillFailAfterRefresh() throws Exception {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        MessageSendPolicyGateway messageSendPolicyGateway = mock(MessageSendPolicyGateway.class);
        MessageRecipientDispatcher dispatcher = mock(MessageRecipientDispatcher.class);
        OfflineQueue offlineQueue = mock(OfflineQueue.class);

        when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());
        when(seqGenerator.next(300L)).thenReturn(7L);
        when(idGenerator.nextId()).thenReturn(9_002L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
        when(messageSendPolicyGateway.resolveGroupRecipientUids(300L, 11L)).thenReturn(List.of(11L, 22L, 33L, 44L));
        when(dispatcher.dispatchGroup(any(GroupMessageDelivery.class)))
            .thenReturn(Map.of(
                22L, MessageDeliveryStatus.DELIVERED,
                33L, MessageDeliveryStatus.ROUTE_STALE,
                44L, MessageDeliveryStatus.WRITE_FAILED
            ))
            .thenReturn(Map.of(
                33L, MessageDeliveryStatus.DELIVERED,
                44L, MessageDeliveryStatus.USER_OFFLINE
            ));

        MessageIngestService service = new MessageIngestService(
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

        service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest()));

        ArgumentCaptor<GroupMessageDelivery> deliveryCaptor = ArgumentCaptor.forClass(GroupMessageDelivery.class);
        verify(dispatcher, times(2)).dispatchGroup(deliveryCaptor.capture());
        List<GroupMessageDelivery> deliveries = deliveryCaptor.getAllValues();
        assertEquals(List.of(11L, 22L, 33L, 44L), deliveries.get(0).recipientUids());
        assertEquals(List.of(33L, 44L), deliveries.get(1).recipientUids());

        ArgumentCaptor<String> offlinePayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(offlineQueue).enqueue(eq(44L), offlinePayloadCaptor.capture(), eq(50));
        verify(offlineQueue, never()).enqueue(eq(22L), anyString(), eq(50));
        verify(offlineQueue, never()).enqueue(eq(33L), anyString(), eq(50));

        String[] payloadSegments = offlinePayloadCaptor.getValue().split("\\|", 3);
        assertEquals(MsgType.GROUP_MESSAGE.name(), payloadSegments[0]);
        assertEquals(SerializerType.PROTOBUF.name(), payloadSegments[1]);
        Mochat.ChatMessageDelivery delivery = Mochat.ChatMessageDelivery.parseFrom(
            Base64.getDecoder().decode(payloadSegments[2])
        );
        assertEquals(9_002L, delivery.getMsgId());
        assertEquals(7L, delivery.getSeq());
        assertEquals(300L, delivery.getConversationId());
        assertEquals(11L, delivery.getFromUid());
        assertEquals(300L, delivery.getGroupPayload().getGroupId());
        assertEquals("hello-group", delivery.getGroupPayload().getText());
    }

    @Test
    void groupRetryAfterDispatcherExceptionQueuesValidRecipientsThatStillFail() {
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        ConversationSeqGenerator seqGenerator = mock(ConversationSeqGenerator.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        RocketMqProducer rocketMqProducer = mock(RocketMqProducer.class);
        MessageSendPolicyGateway messageSendPolicyGateway = mock(MessageSendPolicyGateway.class);
        MessageRecipientDispatcher dispatcher = mock(MessageRecipientDispatcher.class);
        OfflineQueue offlineQueue = mock(OfflineQueue.class);

        when(idempotencyStore.find(11L, 2002L)).thenReturn(Optional.empty());
        when(seqGenerator.next(300L)).thenReturn(7L);
        when(idGenerator.nextId()).thenReturn(9_002L);
        when(rocketMqProducer.publishOrdered(any(MessageAcceptedEvent.class))).thenReturn(true);
        when(messageSendPolicyGateway.resolveGroupRecipientUids(300L, 11L))
            .thenReturn(Arrays.asList(11L, 22L, 33L, null, 0L));
        when(dispatcher.dispatchGroup(any(GroupMessageDelivery.class)))
            .thenThrow(new RuntimeException("gateway unavailable"))
            .thenReturn(Map.of(
                22L, MessageDeliveryStatus.DELIVERED,
                33L, MessageDeliveryStatus.WRITE_FAILED
            ));

        MessageIngestService service = new MessageIngestService(
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

        service.ingest(MessageIngestRequest.groupMessage(11L, 300L, 2002L, 300L, encodedGroupRequest()));

        ArgumentCaptor<GroupMessageDelivery> deliveryCaptor = ArgumentCaptor.forClass(GroupMessageDelivery.class);
        verify(dispatcher, times(2)).dispatchGroup(deliveryCaptor.capture());
        List<GroupMessageDelivery> deliveries = deliveryCaptor.getAllValues();
        assertEquals(Arrays.asList(11L, 22L, 33L, null, 0L), deliveries.get(0).recipientUids());
        assertEquals(List.of(22L, 33L), deliveries.get(1).recipientUids());

        verify(offlineQueue).enqueue(eq(33L), anyString(), eq(50));
        verify(offlineQueue, never()).enqueue(eq(22L), anyString(), eq(50));
    }

    private static String encodedPrivateRequest() {
        return Base64.getEncoder().encodeToString(
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-test")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );
    }

    private static String encodedGroupRequest() {
        return Base64.getEncoder().encodeToString(
            Mochat.GroupMessageReq.newBuilder()
                .setSessionId("session-test")
                .setClientMsgId(2002L)
                .setConversationId(300L)
                .setGroupId(300L)
                .setText("hello-group")
                .build()
                .toByteArray()
        );
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
