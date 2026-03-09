package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.message.contract.MessagePersistencePort;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class RocketMqPersistenceConsumer implements MessageListenerOrderly {
    private static final int FIELD_COUNT = 11;
    private static final long DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS = 3_000L;

    private final DefaultMQPushConsumer delegate;
    private final MessagePersistencePort messagePersistencePort;

    public RocketMqPersistenceConsumer(DefaultMQPushConsumer delegate, MessagePersistencePort messagePersistencePort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messagePersistencePort = Objects.requireNonNull(messagePersistencePort, "messagePersistencePort");
        this.delegate.registerMessageListener(this);
    }

    public void start() {
        try {
            delegate.start();
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start RocketMQ persistence consumer", exception);
        }
    }

    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> messages, ConsumeOrderlyContext context) {
        if (messages == null || messages.isEmpty()) {
            return ConsumeOrderlyStatus.SUCCESS;
        }

        try {
            for (MessageExt message : messages) {
                messagePersistencePort.persist(parse(message));
            }
            return ConsumeOrderlyStatus.SUCCESS;
        } catch (RuntimeException | SQLException exception) {
            if (context != null) {
                context.setSuspendCurrentQueueTimeMillis(DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS);
            }
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        } catch (Exception exception) {
            if (context != null) {
                context.setSuspendCurrentQueueTimeMillis(DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS);
            }
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        }
    }

    private static MessageAcceptedEvent parse(MessageExt message) {
        Objects.requireNonNull(message, "message");
        byte[] body = Objects.requireNonNull(message.getBody(), "message.body");
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Unexpected RocketMQ message envelope field count: " + fields.length);
        }

        long msgId = parseLong(fields[0], "msgId");
        long conversationId = parseLong(fields[1], "conversationId");
        long seq = parseLong(fields[2], "seq");
        long clientMsgId = parseLong(fields[3], "clientMsgId");
        String kind = fields[4];
        long senderUid = parseLong(fields[5], "senderUid");
        Long peerUidLow = parseNullableLong(fields[6]);
        Long peerUidHigh = parseNullableLong(fields[7]);
        Long groupId = parseNullableLong(fields[8]);
        long serverTimeMs = parseLong(fields[9], "serverTimeMs");
        String payloadBase64 = fields[10];

        return switch (kind) {
            case "private" -> MessageAcceptedEvent.privateMessage(
                msgId,
                conversationId,
                seq,
                clientMsgId,
                senderUid,
                peerUidLow,
                peerUidHigh,
                serverTimeMs,
                payloadBase64
            );
            case "group" -> MessageAcceptedEvent.groupMessage(
                msgId,
                conversationId,
                seq,
                clientMsgId,
                senderUid,
                groupId,
                serverTimeMs,
                payloadBase64
            );
            default -> throw new IllegalArgumentException("Unsupported message kind: " + kind);
        };
    }

    private static long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid long for field '" + fieldName + "'", exception);
        }
    }

    private static Long parseNullableLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return parseLong(value, "nullable");
    }
}
