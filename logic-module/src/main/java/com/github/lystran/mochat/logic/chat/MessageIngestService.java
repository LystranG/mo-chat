package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.repository.AllowAllMessageRelationshipRepository;
import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Singleton
public class MessageIngestService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    private static final int OFFLINE_QUEUE_MAX_SIZE = OfflineReplayService.MAX_REPLAY_ITEMS;
    private static final OfflineQueue NO_OP_OFFLINE_QUEUE = new OfflineQueue() {
        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            return List.of();
        }
    };

    private final ConversationLock conversationLock;
    private final IdempotencyStore idempotencyStore;
    private final ConversationSeqGenerator conversationSeqGenerator;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RocketMqProducer rocketMqProducer;
    private final MessageSendPolicyGateway messageSendPolicyGateway;
    private final SenderAckPublisher senderAckPublisher;
    private final MessageRecipientDispatcher messageRecipientDispatcher;
    private final PrivateConversationProgressTracker privateConversationProgressTracker;
    private final OfflineQueue offlineQueue;

    @Inject
    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        RocketMqProducer rocketMqProducer,
        MessageSendPolicyGateway messageSendPolicyGateway,
        SenderAckPublisher senderAckPublisher,
        MessageRecipientDispatcher messageRecipientDispatcher,
        PrivateConversationProgressTracker privateConversationProgressTracker,
        OfflineQueue offlineQueue
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            Clock.systemUTC(),
            rocketMqProducer,
            messageSendPolicyGateway,
            senderAckPublisher,
            messageRecipientDispatcher,
            privateConversationProgressTracker,
            offlineQueue
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            new RepositoryBackedMessageSendPolicyGateway(
                new AllowAllMessageRelationshipRepository(),
                new InMemoryReceiptConversationStateStore()
            ),
            new EventBusSenderAckPublisher(eventBus, DEFAULT_OUTBOUND_TOPIC),
            new EventBusMessageRecipientDispatcher(eventBus, DEFAULT_OUTBOUND_TOPIC),
            new ReceiptConversationProgressTracker(new InMemoryReceiptConversationStateStore()),
            NO_OP_OFFLINE_QUEUE
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus,
        ReceiptConversationStateStore receiptConversationStateStore,
        MessageRelationshipRepository messageRelationshipRepository,
        String outboundTopic
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            new RepositoryBackedMessageSendPolicyGateway(messageRelationshipRepository, receiptConversationStateStore),
            new EventBusSenderAckPublisher(eventBus, outboundTopic),
            new EventBusMessageRecipientDispatcher(eventBus, outboundTopic),
            new ReceiptConversationProgressTracker(receiptConversationStateStore),
            NO_OP_OFFLINE_QUEUE
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus,
        ReceiptConversationStateStore receiptConversationStateStore,
        String outboundTopic
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            eventBus,
            receiptConversationStateStore,
            new AllowAllMessageRelationshipRepository(),
            outboundTopic
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        MessageSendPolicyGateway messageSendPolicyGateway,
        SenderAckPublisher senderAckPublisher,
        MessageRecipientDispatcher messageRecipientDispatcher,
        PrivateConversationProgressTracker privateConversationProgressTracker
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            messageSendPolicyGateway,
            senderAckPublisher,
            messageRecipientDispatcher,
            privateConversationProgressTracker,
            NO_OP_OFFLINE_QUEUE
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        MessageSendPolicyGateway messageSendPolicyGateway,
        SenderAckPublisher senderAckPublisher,
        MessageRecipientDispatcher messageRecipientDispatcher,
        PrivateConversationProgressTracker privateConversationProgressTracker,
        OfflineQueue offlineQueue
    ) {
        this.conversationLock = Objects.requireNonNull(conversationLock, "conversationLock");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.conversationSeqGenerator = Objects.requireNonNull(conversationSeqGenerator, "conversationSeqGenerator");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rocketMqProducer = Objects.requireNonNull(rocketMqProducer, "rocketMqProducer");
        this.messageSendPolicyGateway = Objects.requireNonNull(messageSendPolicyGateway, "messageSendPolicyGateway");
        this.senderAckPublisher = Objects.requireNonNull(senderAckPublisher, "senderAckPublisher");
        this.messageRecipientDispatcher = Objects.requireNonNull(messageRecipientDispatcher, "messageRecipientDispatcher");
        this.privateConversationProgressTracker =
            Objects.requireNonNull(privateConversationProgressTracker, "privateConversationProgressTracker");
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
    }

    public MessageIngestResult ingest(MessageIngestRequest request) {
        Objects.requireNonNull(request, "request");

        AutoCloseable lockHandle = conversationLock.acquire(request.conversationId());
        try {
            var storedResult = idempotencyStore.find(request.senderUid(), request.clientMsgId());
            if (storedResult.isPresent()) {
                var existing = storedResult.get();
                long serverTimeMs = clock.millis();
                senderAckPublisher.publishSendAck(
                    request.senderUid(),
                    request.clientMsgId(),
                    existing.msgId(),
                    existing.seq(),
                    serverTimeMs
                );
                return new MessageIngestResult(request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
            }

            Long privateRecipientUid = null;
            List<Long> groupRecipientUids = List.of();
            if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
                validatePrivateParticipants(request);
                validatePrivatePayload(request);
                privateRecipientUid = resolvePrivateRecipientUid(request.senderUid(), request.peerUidLow(), request.peerUidHigh());
                messageSendPolicyGateway.validatePrivateMessage(request.conversationId(), request.senderUid(), privateRecipientUid);
            } else if (MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
                validateGroupPayload(request);
                Long groupId = request.groupId();
                if (groupId == null || groupId <= 0L) {
                    throw new IllegalArgumentException("group message requires groupId");
                }
                groupRecipientUids = messageSendPolicyGateway.resolveGroupRecipientUids(groupId, request.senderUid());
            } else {
                throw new IllegalArgumentException("unsupported message kind: " + request.kind());
            }

            long seq = conversationSeqGenerator.next(request.conversationId());
            long msgId = idGenerator.nextId();
            long serverTimeMs = clock.millis();
            MessageAcceptedEvent acceptedEvent = toAcceptedEvent(request, msgId, seq, serverTimeMs);

            boolean published;
            try {
                published = rocketMqProducer.publishOrdered(acceptedEvent);
            } catch (IllegalStateException exception) {
                String message = exception.getMessage();
                if (message == null || message.isBlank()) {
                    message = "Ordered publish failed for conversationId=" + request.conversationId();
                }
                MessageRejectException rejection = new MessageRejectException(ErrorCode.MQ_PUBLISH_FAILED, message);
                rejection.initCause(exception);
                throw rejection;
            }
            if (!published) {
                throw new MessageRejectException(
                    ErrorCode.MQ_PUBLISH_FAILED,
                    "Ordered publish failed for conversationId=" + request.conversationId()
                );
            }

            if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
                privateConversationProgressTracker.trackPrivateConversation(
                    request.conversationId(),
                    Objects.requireNonNull(request.peerUidLow(), "peerUidLow"),
                    Objects.requireNonNull(request.peerUidHigh(), "peerUidHigh"),
                    seq
                );
            }
            idempotencyStore.storeIfAbsent(request.senderUid(), request.clientMsgId(), msgId, seq);
            senderAckPublisher.publishSendAck(request.senderUid(), request.clientMsgId(), msgId, seq, serverTimeMs);
            if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
                dispatchPrivateWithOfflineFallback(new PrivateMessageDelivery(
                    request.conversationId(),
                    msgId,
                    seq,
                    serverTimeMs,
                    request.senderUid(),
                    Objects.requireNonNull(privateRecipientUid, "privateRecipientUid"),
                    request.payloadBase64()
                ));
            } else {
                dispatchGroupWithOfflineFallback(new GroupMessageDelivery(
                    request.conversationId(),
                    msgId,
                    seq,
                    serverTimeMs,
                    request.senderUid(),
                    Objects.requireNonNull(request.groupId(), "groupId"),
                    request.payloadBase64(),
                    groupRecipientUids
                ));
            }
            return new MessageIngestResult(request.clientMsgId(), msgId, seq, serverTimeMs);
        } finally {
            closeLock(lockHandle);
        }
    }

    private static MessageAcceptedEvent toAcceptedEvent(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return MessageAcceptedEvent.privateMessage(
                msgId,
                request.conversationId(),
                seq,
                request.clientMsgId(),
                request.senderUid(),
                request.peerUidLow(),
                request.peerUidHigh(),
                serverTimeMs,
                request.payloadBase64()
            );
        }
        if (MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return MessageAcceptedEvent.groupMessage(
                msgId,
                request.conversationId(),
                seq,
                request.clientMsgId(),
                request.senderUid(),
                request.groupId(),
                serverTimeMs,
                request.payloadBase64()
            );
        }
        throw new IllegalArgumentException("unsupported message kind: " + request.kind());
    }

    private void dispatchPrivateWithOfflineFallback(PrivateMessageDelivery delivery) {
        if (dispatchPrivateSafely(delivery) == MessageDeliveryStatus.DELIVERED) {
            return;
        }
        if (dispatchPrivateSafely(delivery) == MessageDeliveryStatus.DELIVERED) {
            return;
        }
        offlineQueue.enqueue(
            delivery.recipientUid(),
            ReplayableDeliveryPayloadCodec.encodePrivate(delivery),
            OFFLINE_QUEUE_MAX_SIZE
        );
    }

    private void dispatchGroupWithOfflineFallback(GroupMessageDelivery delivery) {
        List<Long> retryRecipients = recipientsNeedingRetry(delivery, dispatchGroupSafely(delivery));
        if (retryRecipients.isEmpty()) {
            return;
        }

        Map<Long, MessageDeliveryStatus> retryStatuses = dispatchGroupSafely(new GroupMessageDelivery(
            delivery.conversationId(),
            delivery.msgId(),
            delivery.seq(),
            delivery.serverTimeMs(),
            delivery.senderUid(),
            delivery.groupId(),
            delivery.payloadBase64(),
            retryRecipients
        ));
        String replayablePayload = ReplayableDeliveryPayloadCodec.encodeGroup(delivery);
        for (Long recipientUid : retryRecipients) {
            if (retryStatuses.getOrDefault(recipientUid, MessageDeliveryStatus.WRITE_FAILED) == MessageDeliveryStatus.DELIVERED) {
                continue;
            }
            offlineQueue.enqueue(recipientUid, replayablePayload, OFFLINE_QUEUE_MAX_SIZE);
        }
    }

    private MessageDeliveryStatus dispatchPrivateSafely(PrivateMessageDelivery delivery) {
        try {
            return messageRecipientDispatcher.dispatchPrivate(delivery);
        } catch (RuntimeException dispatchFailure) {
            return MessageDeliveryStatus.WRITE_FAILED;
        }
    }

    private Map<Long, MessageDeliveryStatus> dispatchGroupSafely(GroupMessageDelivery delivery) {
        try {
            Map<Long, MessageDeliveryStatus> statuses = messageRecipientDispatcher.dispatchGroup(delivery);
            return statuses == null ? Map.of() : statuses;
        } catch (RuntimeException dispatchFailure) {
            Map<Long, MessageDeliveryStatus> failures = new LinkedHashMap<>();
            for (Long recipientUid : validGroupRecipients(delivery.recipientUids(), delivery.senderUid())) {
                failures.put(recipientUid, MessageDeliveryStatus.WRITE_FAILED);
            }
            return failures;
        }
    }

    private static List<Long> recipientsNeedingRetry(
        GroupMessageDelivery delivery,
        Map<Long, MessageDeliveryStatus> statuses
    ) {
        return validGroupRecipients(delivery.recipientUids(), delivery.senderUid())
            .stream()
            .filter(recipientUid -> statuses.getOrDefault(recipientUid, MessageDeliveryStatus.WRITE_FAILED) != MessageDeliveryStatus.DELIVERED)
            .toList();
    }

    private static List<Long> validGroupRecipients(List<Long> recipientUids, long senderUid) {
        return recipientUids.stream()
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .filter(recipientUid -> recipientUid > 0L && recipientUid != senderUid)
            .boxed()
            .toList();
    }

    private void validatePrivateParticipants(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        if (request.peerUidLow() == null || request.peerUidHigh() == null) {
            throw new IllegalStateException("private message requires both peer uids");
        }

        if (request.peerUidLow() <= 0 || request.peerUidHigh() <= 0 || request.peerUidLow() >= request.peerUidHigh()) {
            throw new IllegalArgumentException("private conversation participants must be ordered and positive");
        }

        if (request.senderUid() != request.peerUidLow() && request.senderUid() != request.peerUidHigh()) {
            throw new IllegalArgumentException("sender must be one of private conversation peers");
        }
    }

    private void validatePrivatePayload(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        try {
            byte[] body = Base64.getDecoder().decode(request.payloadBase64());
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            if (privateRequest.getNonce().size() != 12) {
                throw new IllegalArgumentException("private message nonce must be exactly 12 bytes");
            }
            if (privateRequest.getCiphertext().isEmpty()) {
                throw new IllegalArgumentException("private message ciphertext is required");
            }
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("invalid private message payload", exception);
        }
    }

    private void validateGroupPayload(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return;
        }

        try {
            byte[] body = Base64.getDecoder().decode(request.payloadBase64());
            var groupRequest = Mochat.GroupMessageReq.parseFrom(body);
            if (!Objects.equals(request.groupId(), groupRequest.getGroupId())) {
                throw new IllegalArgumentException("group message groupId mismatch");
            }
            if (request.conversationId() != groupRequest.getConversationId()) {
                throw new IllegalArgumentException("group message conversationId mismatch");
            }
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("invalid group message payload", exception);
        }
    }

    private long resolvePrivateRecipientUid(long senderUid, Long peerUidLow, Long peerUidHigh) {
        if (peerUidLow == null || peerUidHigh == null) {
            throw new IllegalStateException("private message requires both peer uids");
        }

        if (senderUid == peerUidLow) {
            return peerUidHigh;
        }
        if (senderUid == peerUidHigh) {
            return peerUidLow;
        }

        throw new IllegalStateException("sender must be one of private conversation peers");
    }

    private static void closeLock(AutoCloseable lockHandle) {
        try {
            lockHandle.close();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to release conversation lock", exception);
        }
    }
}
