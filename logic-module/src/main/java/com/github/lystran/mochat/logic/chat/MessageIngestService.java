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

/**
 * 处理消息发送主流程：校验内容、分配序号、写入 MQ，再把确认和消息发给对应用户。
 */
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

    /**
     * 使用默认时钟和默认发送事件通道构造消息发送服务。
     */
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

    /**
     * 使用内存版确认状态仓储和默认关系校验构造消息发送服务。
     */
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

    /**
     * 使用完整依赖构造消息发送服务。
     */
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

    /**
     * 使用默认关系校验构造消息发送服务。
     */
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

    /**
     * 执行消息发送主流程，并返回最终的消息编号与序号。
     */
    public MessageIngestResult ingest(MessageIngestRequest request) {
        Objects.requireNonNull(request, "request");

        // 同一 conversation 的发送流程在锁里串行执行，确保“查重 -> 分配 seq -> 有序发 MQ”看到的是同一条顺序视图，
        // 否则并发重试可能拿到两个 seq，或者让后到消息先进入持久化链路。
        AutoCloseable lockHandle = conversationLock.acquire(request.conversationId());
        try {
            var storedResult = idempotencyStore.find(request.senderUid(), request.clientMsgId());
            if (storedResult.isPresent()) {
                var existing = storedResult.get();
                long serverTimeMs = clock.millis();
                // 客户端重试时如果命中幂等记录，服务端只把上次的确认再回一遍，不会重新生成 msgId / seq。
                emitSendAck(request.senderUid(), request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
                return new MessageIngestResult(request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
            }

            // 先做业务合法性校验，再分配 seq 和 msgId，避免无效请求消耗顺序号。
            validatePrivateConversationParticipants(request);
            validateRelationship(request);
            validatePrivatePayload(request);
            validateGroupPayload(request);
            long seq = conversationSeqGenerator.next(request.conversationId());
            long msgId = idGenerator.nextId();
            long serverTimeMs = clock.millis();
            MessageAcceptedEvent acceptedEvent = toAcceptedEvent(request, msgId, seq, serverTimeMs);

            // 给发送方的确认只能在 RocketMQ 明确写成功后再回；失败时宁可让客户端重试，也不能先回确认再丢消息。
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

    /**
     * 把统一请求改成发给持久化链路的消息记录。
     */
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

    /**
     * 在私聊场景下刷新服务端已知的最新会话状态。
     */
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

    /**
     * 校验私聊消息与已知会话参与者信息是否一致。
     */
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
        // 私聊会话一旦建立，后续发送必须继续使用同一对有序参与者，防止伪造 conversationId 串线。
        if (conversationState.uidLow() != request.peerUidLow() || conversationState.uidHigh() != request.peerUidHigh()) {
            throw new IllegalArgumentException("private conversation participants mismatch");
        }
    }

    /**
     * 校验私聊消息内容是否满足协议要求。
     */
    private void validatePrivatePayload(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        try {
            byte[] body = Base64.getDecoder().decode(request.payloadBase64());
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            if (!privateRequest.hasEncryptedText()) {
                throw new IllegalArgumentException("private message requires encryptedText content");
            }
            var encryptedText = privateRequest.getEncryptedText();
            if (encryptedText.getNonce().size() != 12) {
                throw new IllegalArgumentException("private message nonce must be exactly 12 bytes");
            }
            if (encryptedText.getCiphertext().isEmpty()) {
                throw new IllegalArgumentException("private message ciphertext is required");
            }
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("invalid private message payload", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    /**
     * 校验私聊好友关系或群成员关系是否允许发送消息。
     */
    private void validateRelationship(MessageIngestRequest request) {
        if (MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            MessageRelationshipRepository.PrivateMessageState state = messageRelationshipRepository.privateMessageState(
                request.conversationId(),
                request.peerUidLow(),
                request.peerUidHigh()
            );
            // 先把好友关系查清楚，再给发送方回确认；这样被拉黑或根本不是好友的消息不会进后面的顺序发送链路。
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

    /**
     * 校验群聊消息内容和请求里的群信息是否一致。
     */
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

    /**
     * 给发送方回发送成功确认。
     */
    private void emitSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();

        emitOutboundEvent(senderUid, MsgType.SEND_ACK, sendAck.toByteArray());
    }

    /**
     * 在私聊场景下把消息发给接收方。
     */
    private void emitPrivateDelivery(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        long recipientUid = resolvePrivateRecipientUid(request.senderUid(), request.peerUidLow(), request.peerUidHigh());
        
        var deliveryBuilder = Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .setConversationId(request.conversationId())
            .setFromUid(request.senderUid());
        
        Mochat.PrivatePayload.Builder privatePayloadBuilder = buildPrivatePayloadBuilder(request.payloadBase64(), recipientUid);
        
        if (request.multimediaMetadata() != null) {
            privatePayloadBuilder.setMediaMetadata(convertToProtobuf(request.multimediaMetadata()));
        }
        
        deliveryBuilder.setPrivatePayload(privatePayloadBuilder.build());
        
        var delivery = deliveryBuilder.build();
        emitOutboundEvent(recipientUid, MsgType.PRIVATE_MESSAGE, delivery.toByteArray());
    }

    private void emitGroupDelivery(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (!MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return;
        }

        long groupId = Objects.requireNonNull(request.groupId(), "groupId");
        
        var groupPayloadBuilder = buildGroupPayloadBuilder(request.payloadBase64(), groupId);
        
        if (request.multimediaMetadata() != null) {
            groupPayloadBuilder.setMediaMetadata(convertToProtobuf(request.multimediaMetadata()));
        }
        
        Mochat.GroupPayload groupPayload = groupPayloadBuilder.build();
        
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

    private Mochat.PrivatePayload.Builder buildPrivatePayloadBuilder(String requestPayloadBase64, long recipientUid) {
        try {
            byte[] body = Base64.getDecoder().decode(requestPayloadBase64);
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            if (!privateRequest.hasEncryptedText()) {
                throw new IllegalStateException("private message requires encryptedText content");
            }
            var encryptedText = privateRequest.getEncryptedText();
            return Mochat.PrivatePayload.newBuilder()
                .setToUid(recipientUid)
                .setNonce(encryptedText.getNonce())
                .setCiphertext(encryptedText.getCiphertext());
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build private delivery payload", parseFailure);
        }
    }

    private Mochat.GroupPayload.Builder buildGroupPayloadBuilder(String requestPayloadBase64, long groupId) {
        try {
            byte[] body = Base64.getDecoder().decode(requestPayloadBase64);
            var groupRequest = Mochat.GroupMessageReq.parseFrom(body);
            return Mochat.GroupPayload.newBuilder()
                .setGroupId(groupId)
                .setText(groupRequest.getText());
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build group delivery payload", parseFailure);
        }
    }

    private Mochat.MediaMetadata convertToProtobuf(MessageIngestRequest.MultimediaMetadata metadata) {
        var builder = Mochat.MediaMetadata.newBuilder()
            .setType(convertMediaType(metadata.type()))
            .setMediaUrl(metadata.mediaUrl())
            .setFileSize(metadata.fileSize())
            .setMimeType(metadata.mimeType())
            .setFileName(metadata.fileName());
        
        if (metadata.thumbnailUrl() != null) {
            builder.setThumbnailUrl(metadata.thumbnailUrl());
        }
        if (metadata.duration() != null) {
            builder.setDuration(metadata.duration());
        }
        if (metadata.width() != null) {
            builder.setWidth(metadata.width());
        }
        if (metadata.height() != null) {
            builder.setHeight(metadata.height());
        }
        
        return builder.build();
    }

    private Mochat.MediaType convertMediaType(String type) {
        return switch (type) {
            case "image" -> Mochat.MediaType.IMAGE;
            case "video" -> Mochat.MediaType.VIDEO;
            case "audio" -> Mochat.MediaType.AUDIO;
            case "file" -> Mochat.MediaType.FILE;
            default -> throw new IllegalArgumentException("Unknown media type: " + type);
        };
    }

    /**
     * 根据发送方和有序参与者信息解析私聊接收方 ID。
     */
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

    /**
     * 把协议消息编码成事件总线使用的字符串，再发到连接层。
     */
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

    /**
     * 关闭会话锁句柄，并把受检异常包装成运行时异常。
     */
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
