package com.github.lystran.mochat.call.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.lystran.mochat.call.dto.CallSignalMessage;
import com.github.lystran.mochat.call.entity.CallOfflineNotification;
import com.github.lystran.mochat.call.mapper.CallOfflineNotificationMapper;
import com.github.lystran.mochat.common.id.IdGenerator;

import io.micronaut.context.annotation.Property;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** 群通话离线通知的 MQ 消费者：异步批量入库。 */
@Singleton
public final class CallOfflineNotificationMqConsumer {
    public static final String DEFAULT_CONSUMER_GROUP = "mochat-call-offline-consumer";

    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private DefaultMQPushConsumer consumer;

    public CallOfflineNotificationMqConsumer(
        @Property(name = "mochat.rocketmq.name-server") String nameServer,
        @Property(name = "mochat.rocketmq.offline-notification.topic", defaultValue = CallOfflineNotificationMqProducer.DEFAULT_TOPIC) String topic,
        @Property(name = "mochat.rocketmq.offline-notification.consumer-group", defaultValue = DEFAULT_CONSUMER_GROUP) String consumerGroup,
        SqlSessionFactory sqlSessionFactory,
        ObjectMapper objectMapper,
        IdGenerator idGenerator
    ) {
        this.nameServer = Objects.requireNonNull(nameServer, "mochat.rocketmq.name-server is required");
        this.topic = topic == null || topic.isBlank() ? CallOfflineNotificationMqProducer.DEFAULT_TOPIC : topic;
        this.consumerGroup = consumerGroup == null || consumerGroup.isBlank() ? DEFAULT_CONSUMER_GROUP : consumerGroup;
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @PostConstruct
    public void start() throws MQClientException {
        consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                try {
                    List<CallSignalMessage> messages = objectMapper.readValue(
                        msg.getBody(),
                        new TypeReference<List<CallSignalMessage>>() {}
                    );
                    persistBatch(messages);
                } catch (Exception exception) {
                    // 单条消息处理失败，稍后重试
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    private void persistBatch(List<CallSignalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            CallOfflineNotificationMapper mapper = session.getMapper(CallOfflineNotificationMapper.class);
            for (CallSignalMessage message : messages) {
                CallOfflineNotification notification = new CallOfflineNotification();
                notification.setId(idGenerator.nextId());
                notification.setUserId(message.toUserId());
                notification.setType(message.type());
                notification.setCallId(message.callId());
                notification.setRoomName(message.roomName());
                notification.setGroupId(message.groupId());
                notification.setFromUserId(message.fromUserId());
                try {
                    notification.setPayloadJson(objectMapper.writeValueAsString(message));
                } catch (JsonProcessingException e) {
                    notification.setPayloadJson("{}");
                }
                notification.setCreatedAt(now);
                mapper.insert(notification);
            }
            session.commit();
        }
    }
}
