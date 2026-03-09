package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.logic.repository.AllowAllMessageRelationshipRepository;
import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Objects;

@Singleton
public class MessageIngestService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final ConversationLock conversationLock;
    private final IdempotencyStore idempotencyStore;
    private final ConversationSeqGenerator conversationSeqGenerator;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RocketMqProducer rocketMqProducer;
    private final EventBus eventBus;
    private final ReceiptConversationStateStore receiptConversationStateStore;
    private final MessageRelationshipRepository messageRelationshipRepository;
    private final String outboundTopic;

    @Inject
    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus,
        ReceiptConversationStateStore receiptConversationStateStore,
        MessageRelationshipRepository messageRelationshipRepository
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            Clock.systemUTC(),
            rocketMqProducer,
            eventBus,
            receiptConversationStateStore,
            messageRelationshipRepository,
            DEFAULT_OUTBOUND_TOPIC
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
            eventBus,
            new InMemoryReceiptConversationStateStore(),
            DEFAULT_OUTBOUND_TOPIC
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
        this.conversationLock = Objects.requireNonNull(conversationLock, "conversationLock");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.conversationSeqGenerator = Objects.requireNonNull(conversationSeqGenerator, "conversationSeqGenerator");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rocketMqProducer = Objects.requireNonNull(rocketMqProducer, "rocketMqProducer");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.receiptConversationStateStore = Objects.requireNonNull(receiptConversationStateStore, "receiptConversationStateStore");
        this.messageRelationshipRepository = Objects.requireNonNull(messageRelationshipRepository, "messageRelationshipRepository");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
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

    public MessageIngestResult ingest(MessageIngestRequest request) {
        Objects.requireNonNull(request, "request");

        AutoCloseable lockHandle = conversationLock.acquire(request.conversationId());
        try {
            var storedResult = idempotencyStore.find(request.senderUid(), request.clientMsgId());
            if (storedResult.isPresent()) {
                var existing = storedResult.get();
                long serverTimeMs = clock.millis();
                emitSendAck(request.senderUid(), request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
                return new MessageIngestResult(request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
            }

            validatePrivateConversationParticipants(request);
            validateRelationship(request);
            validatePrivatePayload(request);
            validateGroupPayload(request);
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

            trackPrivateConversation(request, seq);
            idempotencyStore.storeIfAbsent(request.senderUid(), request.clientMsgId(), msgId, seq);
            emitSendAck(request.senderUid(), request.clientMsgId(), msgId, seq, serverTimeMs);
            emitPrivateDelivery(request, msgId, seq, serverTimeMs);
            emitGroupDelivery(request, msgId, seq, serverTimeMs);
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

    private void trackPrivateConversation(MessageIngestRequest request, long seq) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        receiptConversationStateStore.upsertPrivateConversation(
            request.conversationId(),
            request.peerUidLow(),
            request.peerUidHigh(),
            seq
        );
    }

    private void validatePrivateConversationParticipants(MessageIngestRequest request) {
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

        var conversationState = receiptConversationStateStore.findPrivateConversation(request.conversationId())
            .orElseThrow(() -> new IllegalArgumentException("private conversation not found: " + request.conversationId()));
        if (conversationState.uidLow() != request.peerUidLow() || conversationState.uidHigh() != request.peerUidHigh()) {
            throw new IllegalArgumentException("private conversation participants mismatch");
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
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    private void validateRelationship(MessageIngestRequest request) {
        if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            MessageRelationshipRepository.PrivateMessageState state = messageRelationshipRepository.privateMessageState(
                request.conversationId(),
                request.peerUidLow(),
                request.peerUidHigh()
            );
            if (state == MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND) {
                throw new MessageRejectException(ErrorCode.NOT_FRIEND, "private message requires active friendship");
            }
            if (state == MessageRelationshipRepository.PrivateMessageState.BLOCKED) {
                throw new MessageRejectException(ErrorCode.FRIEND_BLOCKED, "friendship is blocked");
            }
            return;
        }

        if (!MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return;
        }

        if (request.groupId() == null || request.groupId() <= 0) {
            throw new IllegalArgumentException("group message requires groupId");
        }

        if (!messageRelationshipRepository.isActiveGroupMember(request.groupId(), request.senderUid())) {
            throw new MessageRejectException(ErrorCode.NOT_IN_GROUP, "sender is not an active group member");
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
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    private void emitSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();

        emitOutboundEvent(senderUid, MsgType.SEND_ACK, sendAck.toByteArray());
    }

    private void emitPrivateDelivery(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        long recipientUid = resolvePrivateRecipientUid(request.senderUid(), request.peerUidLow(), request.peerUidHigh());
        var delivery = Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .setConversationId(request.conversationId())
            .setFromUid(request.senderUid())
            .setPrivatePayload(buildPrivatePayload(request.payloadBase64(), recipientUid))
            .build();

        emitOutboundEvent(recipientUid, MsgType.PRIVATE_MESSAGE, delivery.toByteArray());
    }

    private void emitGroupDelivery(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (!MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return;
        }

        long groupId = Objects.requireNonNull(request.groupId(), "groupId");
        Mochat.GroupPayload groupPayload = buildGroupPayload(request.payloadBase64(), groupId);
        for (Long recipientUid : new LinkedHashSet<>(messageRelationshipRepository.listActiveGroupMemberIds(groupId))) {
            if (recipientUid == null || recipientUid <= 0 || recipientUid == request.senderUid()) {
                continue;
            }

            var delivery = Mochat.ChatMessageDelivery.newBuilder()
                .setMsgId(msgId)
                .setSeq(seq)
                .setServerTimeMs(serverTimeMs)
                .setConversationId(request.conversationId())
                .setFromUid(request.senderUid())
                .setGroupPayload(groupPayload)
                .build();
            emitOutboundEvent(recipientUid, MsgType.GROUP_MESSAGE, delivery.toByteArray());
        }
    }

    private Mochat.PrivatePayload buildPrivatePayload(String requestPayloadBase64, long recipientUid) {
        try {
            byte[] body = Base64.getDecoder().decode(requestPayloadBase64);
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            return Mochat.PrivatePayload.newBuilder()
                .setToUid(recipientUid)
                .setNonce(privateRequest.getNonce())
                .setCiphertext(privateRequest.getCiphertext())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build private delivery payload", parseFailure);
        }
    }

    private Mochat.GroupPayload buildGroupPayload(String requestPayloadBase64, long groupId) {
        try {
            byte[] body = Base64.getDecoder().decode(requestPayloadBase64);
            var groupRequest = Mochat.GroupMessageReq.parseFrom(body);
            return Mochat.GroupPayload.newBuilder()
                .setGroupId(groupId)
                .setText(groupRequest.getText())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build group delivery payload", parseFailure);
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

    private void emitOutboundEvent(long userId, MsgType msgType, byte[] payloadBytes) {
        String encodedPayload = Base64.getEncoder().encodeToString(payloadBytes);
        String outboundEvent = userId
            + "|"
            + msgType.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + encodedPayload;
        eventBus.publish(outboundTopic, outboundEvent);
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
