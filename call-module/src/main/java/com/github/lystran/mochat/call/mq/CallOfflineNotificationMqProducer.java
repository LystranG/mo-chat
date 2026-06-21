package com.github.lystran.mochat.call.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lystran.mochat.call.dto.CallSignalMessage;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 群通话离线通知的 MQ 生产者。 */
@Singleton
public final class CallOfflineNotificationMqProducer {
    public static final String DEFAULT_TOPIC = "mochat.call.offline-notifications";

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public CallOfflineNotificationMqProducer(
        DefaultMQProducer producer,
        ObjectMapper objectMapper,
        @Property(name = "mochat.rocketmq.offline-notification.topic", defaultValue = DEFAULT_TOPIC) String topic
    ) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topic = topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic;
    }

    /**
     * 批量发送离线群通话通知到 MQ。
     *
     * @param messages 离线通知列表
     * @return true 表示全部成功投递到 MQ；false 表示至少有一条发送失败
     */
    public boolean sendBatch(List<CallSignalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return true;
        }
        try {
            String payload = objectMapper.writeValueAsString(messages);
            Message mqMessage = new Message(topic, payload.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(mqMessage);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize offline notifications", exception);
        } catch (Exception exception) {
            return false;
        }
    }
}
