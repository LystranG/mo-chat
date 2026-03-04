package com.github.lystran.mochat.logic.mq;

import com.github.lystran.mochat.logic.chat.MessageIngestEnvelope;
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

@Singleton
public class RocketMqProducer {
    public static final String DEFAULT_TOPIC = "mochat.messages";

    private static final MessageQueueSelector CONVERSATION_SELECTOR = new ConversationMessageQueueSelector();

    private final DefaultMQProducer producer;
    private final String topic;

    public RocketMqProducer(DefaultMQProducer producer) {
        this(producer, DEFAULT_TOPIC);
    }

    public RocketMqProducer(DefaultMQProducer producer, String topic) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    public boolean publishOrdered(MessageIngestEnvelope envelope, String shardingKey) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(shardingKey, "shardingKey");

        Message message = new Message(topic, serialize(envelope).getBytes(StandardCharsets.UTF_8));
        message.setKeys(Long.toString(envelope.msgId()));

        SendResult sendResult;
        try {
            sendResult = producer.send(message, CONVERSATION_SELECTOR, shardingKey);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ordered publish interrupted", interruptedException);
        } catch (MQClientException | MQBrokerException | RemotingException exception) {
            throw new IllegalStateException("Ordered publish failed", exception);
        }

        return sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
    }

    private static String serialize(MessageIngestEnvelope envelope) {
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

    private static String nullableLong(Long value) {
        return value == null ? "" : value.toString();
    }

    private static final class ConversationMessageQueueSelector implements MessageQueueSelector {
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
