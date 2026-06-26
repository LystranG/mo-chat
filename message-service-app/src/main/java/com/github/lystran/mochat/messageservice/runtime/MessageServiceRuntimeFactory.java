package com.github.lystran.mochat.messageservice.runtime;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.infra.redis.RedisEventBus;
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
import com.github.lystran.mochat.runtime.topology.GatewayAddressResolver;
import com.github.lystran.mochat.runtime.topology.GatewayDiscoveryMode;
import com.github.lystran.mochat.runtime.topology.KubernetesDnsGatewayAddressResolver;
import com.github.lystran.mochat.runtime.topology.RuntimeTopologyConfiguration;
import com.github.lystran.mochat.runtime.topology.StaticGatewayAddressResolver;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
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

/**
 * 按配置组装 message-service 运行时需要的基础组件。
 */
@Factory
public final class MessageServiceRuntimeFactory {
    /**
     * 创建 Redis 客户端，供幂等、顺序号、在线路由和离线队列使用。
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.message-service.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    /**
     * 创建 Redis 长连接。
     */
    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * 创建 Redis Pub/Sub 连接，供跨服务的事件总线使用。
     * 没有它，message-service 就收不到 access-gateway 通过 Redis 转发过来的客户端消息。
     *
     * 这里故意不写 @Requires(missingBeans=StatefulRedisPubSubConnection.class)，
     * 否则 EventBus 工厂里的 @Requires(bean=StatefulRedisPubSubConnection.class)
     * 会反过来枚举 PubSub 候选、再触发这里的 missingBeans 检查，
     * MatchesAbsenceOfBeansCondition 评估时直接堆栈溢出。
     * 事实上 message-service-app 里只有这一处提供 StatefulRedisPubSubConnection，
     * missingBeans 守卫也是多余的。
     */
    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    /**
     * 提供同步风格的 Redis 命令入口。
     */
    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    /**
     * 默认使用 Redis 作为跨服务共享的事件总线，让 message-service 能订阅到 access-gateway
     * 转发过来的客户端消息（channel=connection.inbound）。
     *
     * 这里刻意不带 @Requires(missingBeans=EventBus.class)，否则会和
     * MessageIngestService 多构造器组合时在 MatchesAbsenceOfBeansCondition
     * 评估里出现死循环。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(bean = StatefulRedisPubSubConnection.class)
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    /**
     * 提供会话级别的本地锁，避免同一会话并发发消息时顺序号打乱。
     *
     * 故意不写 @Requires(missingBeans = ConversationLock.class)。
     * 该 factory 本身就产出 ConversationLock，再加 missingBeans 守卫会在
     * MatchesAbsenceOfBeansCondition.matches 评估时反向枚举自己的候选，
     * 配合 MessageIngestService 多构造器链路最终堆栈溢出。
     */
    @Singleton
    ConversationLock conversationLock() {
        return new JucConversationLock();
    }

    /**
     * 创建消息 ID 生成器。
     *
     * 同上：missingBeans 守卫会自指，配合 MessageIngestService 多构造器会栈溢出。
     */
    @Singleton
    IdGenerator idGenerator(MessageServiceConfiguration configuration) {
        return new MessageServiceSnowflakeIdGenerator(configuration.getId().getWorkerId());
    }

    /**
     * 创建 Redis 幂等窗口，防止同一个 clientMsgId 被重复接受。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    IdempotencyStore idempotencyStore(RedisCommands<String, String> redisCommands) {
        return new RedisIdempotencyStore(redisCommands);
    }

    /**
     * 创建离线队列，用来暂存在线投递失败后要稍后补发的消息。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    OfflineQueue offlineQueue(RedisCommands<String, String> redisCommands) {
        return new RedisOfflineQueue(redisCommands);
    }

    /**
     * 创建会话顺序号生成器。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    ConversationSeqGenerator conversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        BeanProvider<ConversationStateRepository> conversationStateRepositoryProvider
    ) {
        return new RedisConversationSeqGenerator(
            redisCommands,
            conversationLock,
            // Redis 里没这个会话时，再去仓库读一次最新顺序号，保证从正确位置继续递增。
            conversationId -> conversationStateRepositoryProvider.stream().findFirst()
                .flatMap(repository -> repository.findConversationLatestState(conversationId))
                .map(ConversationStateRepository.ConversationLatestState::latestSeq)
                .orElse(0L)
        );
    }

    /**
     * 创建 RocketMQ 原生生产者。
     */
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

    /**
     * 把原生 RocketMQ 生产者包装成消息域里使用的发送器。
     *
     * 同样去掉 missingBeans 自指守卫。@Replaces 已经能保证不会有其他 RocketMqProducer 候选。
     */
    @Singleton
    @Replaces(RocketMqProducer.class)
    @Requires(bean = DefaultMQProducer.class)
    RocketMqProducer rocketMqProducer(
        DefaultMQProducer defaultMQProducer,
        @Property(name = "mochat.rocketmq.topic") String topic
    ) {
        return new RocketMqProducer(defaultMQProducer, topic);
    }

    /**
     * 创建通过 api-service 做规则校验的网关。
     */
    @Singleton
    @Requires(bean = com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class)
    MessageSendPolicyGateway messageSendPolicyGateway(
        com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub
    ) {
        return new GrpcMessageSendPolicyGateway(stub);
    }

    /**
     * 决定 message-service 应该怎么把网关身份换成实际 gRPC 地址。
     */
    @Singleton
    GatewayAddressResolver gatewayAddressResolver(
        RuntimeTopologyConfiguration runtimeTopologyConfiguration,
        MessageServiceConfiguration configuration
    ) {
        RuntimeTopologyConfiguration.Gateway gateway = runtimeTopologyConfiguration.getGateway();
        GatewayDiscoveryMode discoveryMode = gateway.getDiscoveryMode();
        if (discoveryMode == GatewayDiscoveryMode.AUTO) {
            String podName = runtimeTopologyConfiguration.getPod().getName();
            // 能拿到 pod 名称时，说明更像跑在 K8s 里，就优先按 pod DNS 去找目标网关；
            // 否则退回到静态地址表。
            discoveryMode = (podName == null || podName.isBlank())
                ? GatewayDiscoveryMode.STATIC_MAP
                : GatewayDiscoveryMode.KUBERNETES_DNS;
        }
        if (discoveryMode == GatewayDiscoveryMode.KUBERNETES_DNS) {
            String namespace = gateway.getNamespace();
            if (namespace == null || namespace.isBlank()) {
                namespace = runtimeTopologyConfiguration.getPod().getNamespace();
            }
            return new KubernetesDnsGatewayAddressResolver(
                gateway.getHeadlessService(),
                namespace,
                gateway.getClusterDomain(),
                gateway.getGrpcPort()
            );
        }
        java.util.Map<String, String> staticTargets = gateway.getStaticTargets().isEmpty()
            ? configuration.getRoute().getGatewayTargets()
            : gateway.getStaticTargets();
        return new StaticGatewayAddressResolver(staticTargets);
    }

    /**
     * 创建真正负责在线投递的分发器。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(bean = AccessGatewayDispatchClientFactory.class)
    MessageRecipientDispatcher messageRecipientDispatcher(
        RedisCommands<String, String> redisCommands,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        GatewayAddressResolver gatewayAddressResolver
    ) {
        return new GrpcMessageRecipientDispatcher(
            redisCommands,
            accessGatewayDispatchClientFactory,
            gatewayAddressResolver
        );
    }

    /**
     * 提供一个兜底分发器。
     */
    @Singleton
    @Secondary
    MessageRecipientDispatcher noOpMessageRecipientDispatcher() {
        return new MessageRecipientDispatcher() {
            /**
             * 没有真实网关可用时，私聊统一按“对方当前不在线”处理。
             */
            @Override
            public MessageDeliveryStatus dispatchPrivate(com.github.lystran.mochat.logic.chat.PrivateMessageDelivery delivery) {
                return MessageDeliveryStatus.USER_OFFLINE;
            }

            /**
             * 没有真实网关可用时，群聊返回空结果，让上层自己决定是否转离线队列。
             */
            @Override
            public java.util.Map<Long, MessageDeliveryStatus> dispatchGroup(
                com.github.lystran.mochat.logic.chat.GroupMessageDelivery delivery
            ) {
                return java.util.Map.of();
            }
        };
    }

    /**
     * 提供一个兜底的发送方确认器，避免缺少实现时直接报错。
     *
     * 同样去掉 missingBeans 自指守卫，避免 StackOverflow。
     */
    @Singleton
    SenderAckPublisher senderAckPublisher() {
        return (senderUid, clientMsgId, msgId, seq, serverTimeMs) -> {
        };
    }

    /**
     * 提供一个兜底的私聊进度记录器，避免缺少实现时影响主流程。
     *
     * 这里 missingBeans 守卫的对象（ReceiptConversationStateStore）和产出类型
     * （PrivateConversationProgressTracker）不一样，理论上不该自指。
     * 但实测仍会触发同一个评估路径里的递归，一并去掉。
     */
    @Singleton
    PrivateConversationProgressTracker privateConversationProgressTracker() {
        return (conversationId, peerUidLow, peerUidHigh, seq) -> {
        };
    }
}
