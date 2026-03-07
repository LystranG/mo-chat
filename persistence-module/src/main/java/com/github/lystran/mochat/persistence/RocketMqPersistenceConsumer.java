package com.github.lystran.mochat.persistence;

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
    private final MqConsumer mqConsumer;

    public RocketMqPersistenceConsumer(DefaultMQPushConsumer delegate, MqConsumer mqConsumer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mqConsumer = Objects.requireNonNull(mqConsumer, "mqConsumer");
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
                mqConsumer.persistMessage(parse(message));
            }
            return ConsumeOrderlyStatus.SUCCESS;
        } catch (RuntimeException | SQLException exception) {
            if (context != null) {
                context.setSuspendCurrentQueueTimeMillis(DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS);
            }
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        }
    }

    private static MessageRepository.PersistedMessage parse(MessageExt message) {
        Objects.requireNonNull(message, "message");
        byte[] body = Objects.requireNonNull(message.getBody(), "message.body");
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Unexpected RocketMQ message envelope field count: " + fields.length);
        }

        return new MessageRepository.PersistedMessage(
            parseLong(fields[0], "msgId"),
            parseLong(fields[1], "conversationId"),
            parseLong(fields[2], "seq"),
            parseLong(fields[3], "clientMsgId"),
            fields[4],
            parseLong(fields[5], "senderUid"),
            parseNullableLong(fields[6]),
            parseNullableLong(fields[7]),
            parseNullableLong(fields[8]),
            parseLong(fields[9], "serverTimeMs"),
            fields[10]
        );
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
