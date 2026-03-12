package com.github.lystran.mochat.messageservice.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.chat.MessageDeliveryStatus;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway;
import com.github.lystran.mochat.logic.chat.PrivateConversationProgressTracker;
import com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore;
import com.github.lystran.mochat.logic.chat.SenderAckPublisher;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageRecipientDispatcher;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageSendPolicyGateway;
import com.github.lystran.mochat.infra.redis.RedisConversationSeqGenerator;
import com.github.lystran.mochat.infra.redis.RedisIdempotencyStore;
import com.github.lystran.mochat.infra.redis.RedisOfflineQueue;
import com.github.lystran.mochat.runtime.config.MessageServiceConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

@Factory
public final class MessageServiceRuntimeFactory {
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.message-service.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    @Singleton
    @Requires(missingBeans = ConversationLock.class)
    ConversationLock conversationLock() {
        return new JucConversationLock();
    }

    @Singleton
    @Requires(missingBeans = IdGenerator.class)
    IdGenerator idGenerator(MessageServiceConfiguration configuration) {
        return new MessageServiceSnowflakeIdGenerator(configuration.getId().getWorkerId());
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(missingBeans = IdempotencyStore.class)
    IdempotencyStore idempotencyStore(RedisCommands<String, String> redisCommands) {
        return new RedisIdempotencyStore(redisCommands);
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(missingBeans = OfflineQueue.class)
    OfflineQueue offlineQueue(RedisCommands<String, String> redisCommands) {
        return new RedisOfflineQueue(redisCommands);
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(missingBeans = ConversationSeqGenerator.class)
    ConversationSeqGenerator conversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        BeanProvider<ConversationStateRepository> conversationStateRepositoryProvider
    ) {
        return new RedisConversationSeqGenerator(
            redisCommands,
            conversationLock,
            conversationId -> conversationStateRepositoryProvider.stream().findFirst()
                .flatMap(repository -> repository.findConversationLatestState(conversationId))
                .map(ConversationStateRepository.ConversationLatestState::latestSeq)
                .orElse(0L)
        );
    }

    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.message-service.dependencies.mq-enabled", notEquals = "false", defaultValue = "true")
    DefaultMQProducer defaultMqProducer(
        @Property(name = "mochat.rocketmq.name-server") String nameServer,
        @Property(name = "mochat.rocketmq.producer-group") String producerGroup
    ) {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        try {
            producer.start();
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start RocketMQ producer", exception);
        }
        return producer;
    }

    @Singleton
    @Replaces(RocketMqProducer.class)
    @Requires(bean = DefaultMQProducer.class)
    @Requires(missingBeans = RocketMqProducer.class)
    RocketMqProducer rocketMqProducer(
        DefaultMQProducer defaultMQProducer,
        @Property(name = "mochat.rocketmq.topic") String topic
    ) {
        return new RocketMqProducer(defaultMQProducer, topic);
    }

    @Singleton
    @Requires(bean = com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class)
    MessageSendPolicyGateway messageSendPolicyGateway(
        com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub
    ) {
        return new GrpcMessageSendPolicyGateway(stub);
    }

    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(bean = AccessGatewayDispatchClientFactory.class)
    MessageRecipientDispatcher messageRecipientDispatcher(
        RedisCommands<String, String> redisCommands,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        MessageServiceConfiguration configuration
    ) {
        return new GrpcMessageRecipientDispatcher(
            redisCommands,
            accessGatewayDispatchClientFactory,
            configuration.getRoute().getGatewayTargets()
        );
    }

    @Singleton
    @Secondary
    MessageRecipientDispatcher noOpMessageRecipientDispatcher() {
        return new MessageRecipientDispatcher() {
            @Override
            public MessageDeliveryStatus dispatchPrivate(com.github.lystran.mochat.logic.chat.PrivateMessageDelivery delivery) {
                return MessageDeliveryStatus.USER_OFFLINE;
            }

            @Override
            public java.util.Map<Long, MessageDeliveryStatus> dispatchGroup(
                com.github.lystran.mochat.logic.chat.GroupMessageDelivery delivery
            ) {
                return java.util.Map.of();
            }
        };
    }

    @Singleton
    @Requires(missingBeans = SenderAckPublisher.class)
    SenderAckPublisher senderAckPublisher() {
        return (senderUid, clientMsgId, msgId, seq, serverTimeMs) -> {
        };
    }

    @Singleton
    @Requires(missingBeans = ReceiptConversationStateStore.class)
    PrivateConversationProgressTracker privateConversationProgressTracker() {
        return (conversationId, peerUidLow, peerUidHigh, seq) -> {
        };
    }
}
