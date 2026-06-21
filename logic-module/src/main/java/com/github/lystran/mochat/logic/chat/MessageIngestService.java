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

/**
 * 负责接收消息、分配消息 ID 和顺序号、写 MQ、给发送方回确认，并安排在线或离线投递。
 */
@Singleton
public class MessageIngestService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    private static final int OFFLINE_QUEUE_MAX_SIZE = OfflineReplayService.MAX_REPLAY_ITEMS;
    // 某些本地或兼容路径没有真正的离线队列时，先用这个空实现兜住流程。
    private static final OfflineQueue NO_OP_OFFLINE_QUEUE = new OfflineQueue() {
        /**
         * 空实现，不真正写入离线队列。
         */
        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
        }

        /**
         * 空实现，不返回任何离线消息。
         */
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

    /**
     * 创建生产环境默认使用的消息接收服务。
     */
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

    /**
     * 为基于事件总线的运行方式创建消息接收服务。
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

    /**
     * 为事件总线运行方式创建消息接收服务，并显式指定回执仓库和关系仓库。
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

    /**
     * 为事件总线运行方式创建消息接收服务，并使用默认允许发送的关系规则。
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
     * 创建一个不带离线队列的消息接收服务。
     */
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

    /**
     * 创建完整的消息接收服务。
     */
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

    /**
     * 接收一条消息并推进完整的 message-service 同步链路。
     */
    public MessageIngestResult ingest(MessageIngestRequest request) {
        Objects.requireNonNull(request, "request");


        AutoCloseable lockHandle = conversationLock.acquire(request.conversationId());
        try {
            var storedResult = idempotencyStore.find(request.senderUid(), request.clientMsgId());
            if (storedResult.isPresent()) {
                var existing = storedResult.get();
                long serverTimeMs = clock.millis();
                // 命中幂等窗口时，说明这条消息之前已经被接受过了，这里直接把原来的结果再回给发送方。
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
                // 私聊除了检查消息正文，还要先把双方用户关系校验清楚。
                validatePrivateParticipants(request);
                validatePrivatePayload(request);
                privateRecipientUid = resolvePrivateRecipientUid(request.senderUid(), request.peerUidLow(), request.peerUidHigh());
                messageSendPolicyGateway.validatePrivateMessage(request.conversationId(), request.senderUid(), privateRecipientUid);
            } else if (MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
                // 群聊需要确认群存在、发送人还在群里，并拿到这次真正要投递的成员列表。
                validateGroupPayload(request);
                Long groupId = request.groupId();
                if (groupId == null || groupId <= 0L) {
                    throw new IllegalArgumentException("group message requires groupId");
                }
                groupRecipientUids = messageSendPolicyGateway.resolveGroupRecipientUids(groupId, request.senderUid());
            } else {
                throw new IllegalArgumentException("unsupported message kind: " + request.kind());
            }

            // 顺序号代表会话内先后顺序，消息 ID 代表全局唯一身份，两者都在接受时一次性确定。
            long seq = conversationSeqGenerator.next(request.conversationId());
            long msgId = idGenerator.nextId();
            long serverTimeMs = clock.millis();
            MessageAcceptedEvent acceptedEvent = toAcceptedEvent(request, msgId, seq, serverTimeMs);

            boolean published;
            try {
                // 先把消息成功写进 MQ，后面才能说“服务已经接受”。
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
                // 私聊回执要知道这条会话已经推进到哪个顺序号，所以这里顺手更新基础进度。
                privateConversationProgressTracker.trackPrivateConversation(
                    request.conversationId(),
                    Objects.requireNonNull(request.peerUidLow(), "peerUidLow"),
                    Objects.requireNonNull(request.peerUidHigh(), "peerUidHigh"),
                    seq
                );
            }
            // 只有 MQ 明确接收成功后，才记录幂等结果并给发送方回确认。
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

    /**
     * 把连接层转来的请求改成要写进 MQ 的消息事件。
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
     * 尝试在线投递私聊；如果当前路由和刷新后路由都不通，就改走离线队列。
     */
    private void dispatchPrivateWithOfflineFallback(PrivateMessageDelivery delivery) {
        if (dispatchPrivateSafely(delivery) == MessageDeliveryStatus.DELIVERED) {
            return;
        }
        // 第二次再次投递时会重新查 Redis 路由，相当于只刷新一次在线路由。
        if (dispatchPrivateSafely(delivery) == MessageDeliveryStatus.DELIVERED) {
            return;
        }
        // 如果这条路走不通，就把消息正文打包后写入离线队列，等登录后再补发。
        offlineQueue.enqueue(
            delivery.recipientUid(),
            ReplayableDeliveryPayloadCodec.encodePrivate(delivery),
            OFFLINE_QUEUE_MAX_SIZE
        );
    }

    /**
     * 尝试在线投递群消息；仍失败的成员改走离线队列。
     */
    private void dispatchGroupWithOfflineFallback(GroupMessageDelivery delivery) {
        List<Long> retryRecipients = recipientsNeedingRetry(delivery, dispatchGroupSafely(delivery));
        if (retryRecipients.isEmpty()) {
            return;
        }

        // 这里只对第一次没送到的人再查一次路由，避免同步链路无限重试。
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
        // 离线队列里给每个成员放的是同一份“可补发字符串消息”。
        String replayablePayload = ReplayableDeliveryPayloadCodec.encodeGroup(delivery);
        for (Long recipientUid : retryRecipients) {
            if (retryStatuses.getOrDefault(recipientUid, MessageDeliveryStatus.WRITE_FAILED) == MessageDeliveryStatus.DELIVERED) {
                continue;
            }
            offlineQueue.enqueue(recipientUid, replayablePayload, OFFLINE_QUEUE_MAX_SIZE);
        }
    }

    /**
     * 安全地执行一次私聊在线投递，异常统一按写连接失败处理。
     */
    private MessageDeliveryStatus dispatchPrivateSafely(PrivateMessageDelivery delivery) {
        try {
            return messageRecipientDispatcher.dispatchPrivate(delivery);
        } catch (RuntimeException dispatchFailure) {
            return MessageDeliveryStatus.WRITE_FAILED;
        }
    }

    /**
     * 安全地执行一次群消息在线投递，异常统一按失败处理。
     */
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

    /**
     * 找出第一次在线投递后还需要重试的群成员。
     */
    private static List<Long> recipientsNeedingRetry(
        GroupMessageDelivery delivery,
        Map<Long, MessageDeliveryStatus> statuses
    ) {
        return validGroupRecipients(delivery.recipientUids(), delivery.senderUid())
            .stream()
            .filter(recipientUid -> statuses.getOrDefault(recipientUid, MessageDeliveryStatus.WRITE_FAILED) != MessageDeliveryStatus.DELIVERED)
            .toList();
    }

    /**
     * 过滤掉空值、非法用户和发送人自己。
     */
    private static List<Long> validGroupRecipients(List<Long> recipientUids, long senderUid) {
        return recipientUids.stream()
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .filter(recipientUid -> recipientUid > 0L && recipientUid != senderUid)
            .boxed()
            .toList();
    }

    /**
     * 校验私聊参与人信息是否完整且排序正确。
     */
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

    /**
     * 校验私聊消息正文格式是否合法。
     */
    private void validatePrivatePayload(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        try {
            byte[] body = Base64.getDecoder().decode(request.payloadBase64());
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            // 私聊正文里至少要带固定长度的 nonce 和真正的密文内容。
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

    /**
     * 校验群聊消息正文里的群和会话信息是否跟请求头一致。
     */
    private void validateGroupPayload(MessageIngestRequest request) {
        if (!MessageIngestRequest.KIND_GROUP.equals(request.kind())) {
            return;
        }

        try {
            byte[] body = Base64.getDecoder().decode(request.payloadBase64());
            var groupRequest = Mochat.GroupMessageReq.parseFrom(body);
            // 这里要防止“请求头说一回事，消息正文里又是另一回事”的串线情况。
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

    /**
     * 根据私聊双方 ID 算出这条消息真正的接收人。
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
     * 释放会话锁。
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
