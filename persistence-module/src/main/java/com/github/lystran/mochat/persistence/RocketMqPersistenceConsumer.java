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

/**
 * 负责从 RocketMQ 收消息、拆开内容，再交给落库入口去处理。
 */
public final class RocketMqPersistenceConsumer implements MessageListenerOrderly {
    private static final int FIELD_COUNT = 11;
    private static final long DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS = 3_000L;

    private final DefaultMQPushConsumer delegate;
    private final MessagePersistencePort messagePersistencePort;

    // 注册 RocketMQ 监听器，收到消息后再转给真正的落库入口。
    public RocketMqPersistenceConsumer(DefaultMQPushConsumer delegate, MessagePersistencePort messagePersistencePort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messagePersistencePort = Objects.requireNonNull(messagePersistencePort, "messagePersistencePort");
        this.delegate.registerMessageListener(this);
    }

    // 启动底层 RocketMQ 收消息组件。
    public void start() {
        try {
            delegate.start();
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start RocketMQ persistence consumer", exception);
        }
    }

    // 关闭底层 RocketMQ 收消息组件。
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    // 按顺序处理这一批消息，只要有一条失败，就让 RocketMQ 过一会儿重试当前队列。
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
                // 这里选择暂停重试，而不是跳过坏消息继续往下跑，免得把同一会话里的顺序打乱。
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

    // 把 RocketMQ 消息体里的字符串拆开，还原成系统内部统一使用的消息对象。
    private static MessageAcceptedEvent parse(MessageExt message) {
        Objects.requireNonNull(message, "message");
        byte[] body = Objects.requireNonNull(message.getBody(), "message.body");
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException("Unexpected RocketMQ message envelope field count: " + fields.length);
        }

        // 这串文本里同时塞了通用字段和“私聊/群聊二选一”的字段，后面再按 kind 分开还原。
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

    // 解析必填数字字段；失败时把字段名带出来，方便知道是哪一段消息写坏了。
    private static long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid long for field '" + fieldName + "'", exception);
        }
    }

    // 解析允许留空的数字字段，给私聊/群聊那几组互斥字段共用。
    private static Long parseNullableLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return parseLong(value, "nullable");
    }
}
