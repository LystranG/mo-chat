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
 * 负责把已验收的消息按会话维度顺序写入 RocketMQ。
 */
@Singleton
public class RocketMqProducer {
    public static final String DEFAULT_TOPIC = "mochat.messages";

    private static final MessageQueueSelector CONVERSATION_SELECTOR = new ConversationMessageQueueSelector();

    private final DefaultMQProducer producer;
    private final String topic;

    /**
     * 使用默认 RocketMQ 主题名（也就是消息发到哪一类）构造有序发送器。
     */
    public RocketMqProducer(DefaultMQProducer producer) {
        this(producer, DEFAULT_TOPIC);
    }

    /**
     * 使用指定 RocketMQ 主题名（也就是消息发到哪一类）构造有序发送器。
     */
    public RocketMqProducer(DefaultMQProducer producer, String topic) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    /**
     * 将消息按会话分片键发送到固定队列，保证同一会话内的消费顺序。
     */
    public boolean publishOrdered(MessageAcceptedEvent acceptedEvent) {
        Objects.requireNonNull(acceptedEvent, "acceptedEvent");

        // msgId 作为 RocketMQ key，方便后面在 broker 或控制台里定位具体是哪条消息。
        Message message = new Message(topic, serialize(acceptedEvent).getBytes(StandardCharsets.UTF_8));
        message.setKeys(Long.toString(acceptedEvent.msgId()));

        SendResult sendResult;
        try {
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
     * 把领域事件编码成当前客户端发送时使用的竖线拼接字符串。
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
     * 将可空 Long 编码为空串或数字文本。
     */
    private static String nullableLong(Long value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 按会话分片键把消息稳定路由到同一个 RocketMQ 队列。
     */
    private static final class ConversationMessageQueueSelector implements MessageQueueSelector {
        /**
         * 根据分片键哈希结果选择目标消息队列。
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
