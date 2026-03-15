package com.github.lystran.mochat.logic.mq;

import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 负责把已接受的消息按会话顺序同步写入 RocketMQ。
 */
@Singleton
public class RocketMqProducer {
    public static final String DEFAULT_TOPIC = "mochat.messages";

    private static final MessageQueueSelector CONVERSATION_SELECTOR = new ConversationMessageQueueSelector();

    private final DefaultMQProducer producer;
    private final String topic;

    /**
     * 使用默认主题创建 RocketMQ 发送器。
     */
    public RocketMqProducer(DefaultMQProducer producer) {
        this(producer, DEFAULT_TOPIC);
    }

    /**
     * 使用指定主题创建 RocketMQ 发送器。
     */
    public RocketMqProducer(DefaultMQProducer producer, String topic) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    /**
     * 按会话顺序把消息同步写入 RocketMQ。
     */
    public boolean publishOrdered(MessageAcceptedEvent acceptedEvent) {
        Objects.requireNonNull(acceptedEvent, "acceptedEvent");

        // MQ 里保存的是一条用竖线拼出来的字符串消息，方便消费侧直接拆开处理。
        Message message = new Message(topic, serialize(acceptedEvent).getBytes(StandardCharsets.UTF_8));
        message.setKeys(Long.toString(acceptedEvent.msgId()));

        SendResult sendResult;
        try {
            // 同一会话始终选择同一条队列，这样消费侧更容易按顺序处理。
            sendResult = producer.send(message, CONVERSATION_SELECTOR, acceptedEvent.shardingKey());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ordered publish interrupted", interruptedException);
        } catch (MQClientException | MQBrokerException | RemotingException exception) {
            throw new IllegalStateException("Ordered publish failed", exception);
        }

        return sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
    }

    /**
     * 把消息事件整理成可写入 MQ 的字符串。
     */
    private static String serialize(MessageAcceptedEvent envelope) {
        return envelope.msgId()
            + "|"
            + envelope.conversationId()
            + "|"
            + envelope.seq()
            + "|"
            + envelope.clientMsgId()
            + "|"
            + envelope.kind()
            + "|"
            + envelope.senderUid()
            + "|"
            + nullableLong(envelope.peerUidLow())
            + "|"
            + nullableLong(envelope.peerUidHigh())
            + "|"
            + nullableLong(envelope.groupId())
            + "|"
            + envelope.serverTimeMs()
            + "|"
            + envelope.payloadBase64();
    }

    /**
     * 把可空的 Long 转成字符串，空值就写成空串。
     */
    private static String nullableLong(Long value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 根据会话分片键选择目标队列，尽量保证同一会话有固定顺序。
     */
    private static final class ConversationMessageQueueSelector implements MessageQueueSelector {
        /**
         * 按分片键稳定选择一条 RocketMQ 队列。
         */
        @Override
        public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
            if (mqs == null || mqs.isEmpty()) {
                throw new IllegalStateException("No message queue available for ordered send");
            }

            int index = Math.floorMod(Objects.hashCode(arg), mqs.size());
            return mqs.get(index);
        }
    }
}
