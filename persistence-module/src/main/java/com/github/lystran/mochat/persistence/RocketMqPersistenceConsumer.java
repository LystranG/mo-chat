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
 * 把 RocketMQ 拉下来的消息按顺序交给数据库落库入口处理。
 */
public final class RocketMqPersistenceConsumer implements MessageListenerOrderly {
    private static final int FIELD_COUNT = 11;
    private static final long DEFAULT_SUSPEND_CURRENT_QUEUE_TIME_MILLIS = 3_000L;

    private final DefaultMQPushConsumer delegate;
    private final MessagePersistencePort messagePersistencePort;

    /**
     * 收下 RocketMQ 消费端，并把自己登记成顺序消息监听器。
     */
    public RocketMqPersistenceConsumer(DefaultMQPushConsumer delegate, MessagePersistencePort messagePersistencePort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messagePersistencePort = Objects.requireNonNull(messagePersistencePort, "messagePersistencePort");
        this.delegate.registerMessageListener(this);
    }

    /**
     * 启动 RocketMQ 消费线程。
     */
    public void start() {
        try {
            delegate.start();
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start RocketMQ persistence consumer", exception);
        }
    }

    /**
     * 停掉 RocketMQ 消费线程。
     */
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    /**
     * 按顺序处理这一批消息；只要有一条失败，就让 RocketMQ 稍后重试当前队列。
     */
    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> messages, ConsumeOrderlyContext context) {
        if (messages == null || messages.isEmpty()) {
            return ConsumeOrderlyStatus.SUCCESS;
        }

        try {
            for (MessageExt message : messages) {
                // 每条消息都先拆回统一对象，再交给真正的落库入口。
                messagePersistencePort.persist(parse(message));
            }
            return ConsumeOrderlyStatus.SUCCESS;
        } catch (RuntimeException | SQLException exception) {
            if (context != null) {
                // 顺序消费失败时先暂停一小会，避免当前队列被无意义地立刻重刷。
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

    /**
     * 把 RocketMQ 里用竖线拼起来的消息内容拆回系统内部统一对象。
     */
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

    /**
     * 把字符串字段安全地转成 long。
     */
    private static long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid long for field '" + fieldName + "'", exception);
        }
    }

    /**
     * 把可空数字字段转成 Long；空串表示数据库里本来就没有这个值。
     */
    private static Long parseNullableLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return parseLong(value, "nullable");
    }
}
