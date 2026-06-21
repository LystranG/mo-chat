package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.connection.ChatChannelInitializer;
import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.connection.OutboundEventSubscriber;
import com.github.lystran.mochat.infra.redis.RedisConversationSeqGenerator;
import com.github.lystran.mochat.infra.redis.RedisEventBus;
import com.github.lystran.mochat.infra.redis.RedisIdempotencyStore;
import com.github.lystran.mochat.infra.redis.RedisOfflineQueue;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.chat.JdbcReceiptConversationStateStore;
import com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore;
import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import com.github.lystran.mochat.persistence.ConversationRepository;
import com.github.lystran.mochat.persistence.MessageRepository;
import com.github.lystran.mochat.persistence.MqConsumer;
import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.time.Duration;

/**
 * 集中创建单体运行模式下的基础组件。
 */
@Factory
public final class MochatRuntimeFactory {
    /**
     * 创建 PostgreSQL 数据源。
     */
    @Singleton
    @Requires(missingBeans = DataSource.class)
    DataSource dataSource(
        @Property(name = "mochat.postgres.url") String url,
        @Property(name = "mochat.postgres.username") String username,
        @Property(name = "mochat.postgres.password") String password
    ) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * 创建 Flyway，用来管理数据库表结构升级。
     */
    @Singleton
    Flyway flyway(DataSource dataSource, @Property(name = "mochat.flyway.locations") String flywayLocations) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(flywayLocations)
            .load();
    }

    /**
     * 创建 Redis 客户端。
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    /**
     * 创建普通 Redis 连接。
     */
    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * 暴露同步版 Redis 命令入口。
     */
    @Singleton
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    /**
     * 创建 Redis 订阅连接，给事件总线使用。
     */
    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    /**
     * 创建跨进程事件总线。
     */
    @Singleton
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    /**
     * 创建离线消息队列。
     */
    @Singleton
    OfflineQueue offlineQueue(RedisCommands<String, String> redisCommands) {
        return new RedisOfflineQueue(redisCommands);
    }

    /**
     * 创建会话级别的串行锁，避免同一会话并发写乱顺序。
     */
    @Singleton
    ConversationLock conversationLock() {
        return new JucConversationLock();
    }

    /**
     * 创建会话序号生成器。
     */
    @Singleton
    ConversationSeqGenerator conversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        ConversationStateRepository conversationStateRepository
    ) {
        return new RedisConversationSeqGenerator(
            redisCommands,
            conversationLock,
            // Redis 里没有现成序号时，回到数据库补一次最新进度，避免服务重启后从 0 重新开始。
            // 先用数据库里的最新顺序号接上，避免服务重启后顺序号往回跳。
           conversationId -> conversationStateRepository.findConversationLatestState(conversationId)
                .map(ConversationStateRepository.ConversationLatestState::latestSeq)
                .orElse(0L)
        );
    }

    /**
     * 创建幂等记录存储，避免同一请求被重复处理。
     */
    @Singleton
    IdempotencyStore idempotencyStore(RedisCommands<String, String> redisCommands) {
        return new RedisIdempotencyStore(redisCommands);
    }

    /**
     * 创建“用户在线通道”目录。
     */
    @Singleton
    UserChannelDirectory<Channel> userChannelDirectory() {
        return new InMemoryChannelDirectory<>();
    }

    /**
     * 创建全局 id 生成器。
     */
    @Singleton
    IdGenerator idGenerator(@Property(name = "mochat.id.worker-id", defaultValue = "1") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    /**
     * 在旧持久化模式打开时，创建消息仓库。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    MessageRepository messageRepository() {
        return new MessageRepository();
    }

    /**
     * 在旧持久化模式打开时，创建会话仓库。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    ConversationRepository conversationRepository() {
        return new ConversationRepository();
    }

    /**
     * 在旧持久化模式打开时，创建群消息缓存。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    GroupMessageCache groupMessageCache(RedisCommands<String, String> redisCommands) {
        return new GroupMessageCache(redisCommands);
    }

    /**
     * 在旧持久化模式打开时，创建回执进度存储。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    ReceiptConversationStateStore receiptConversationStateStore(DataSource dataSource) {
        return new JdbcReceiptConversationStateStore(dataSource);
    }

    /**
     * 在旧持久化模式打开时，创建消息入库消费者。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    MqConsumer mqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        return new MqConsumer(dataSource, messageRepository, conversationRepository, groupMessageCache);
    }

    /**
     * 创建 RocketMQ 消费端，并订阅要入库的主题。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    DefaultMQPushConsumer defaultMqPushConsumer(
        @Property(name = "mochat.rocketmq.name-server") String nameServer,
        @Property(name = "mochat.rocketmq.consumer-group", defaultValue = "mochat-persistence-consumer") String consumerGroup,
        @Property(name = "mochat.rocketmq.topic") String topic
    ) {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        try {
            consumer.subscribe(topic, "*");
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to configure RocketMQ persistence consumer", exception);
        }
        return consumer;
    }

    /**
     * 把 RocketMQ 原生消费者包装成项目里的持久化消费者。
     */
    @Singleton
    @Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
    RocketMqPersistenceConsumer rocketMqPersistenceConsumer(
        DefaultMQPushConsumer defaultMqPushConsumer,
        MqConsumer mqConsumer
    ) {
        return new RocketMqPersistenceConsumer(defaultMqPushConsumer, mqConsumer);
    }

    /**
     * 创建 RocketMQ 生产端，负责把待处理消息写入队列。
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
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
     * 用工厂里创建好的 RocketMQ 生产端替换默认实现。
     */
    @Singleton
    @Replaces(RocketMqProducer.class)
    RocketMqProducer rocketMqProducer(
        DefaultMQProducer defaultMqProducer,
        @Property(name = "mochat.rocketmq.topic") String topic
    ) {
        return new RocketMqProducer(defaultMqProducer, topic);
    }

    /**
     * 创建下行消息订阅器，把待发消息发给在线用户，或转存离线队列。
     */
    @Singleton
    OutboundEventSubscriber outboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        return new OutboundEventSubscriber(eventBus, userChannelDirectory, offlineQueue);
    }

    /**
     * 创建 Netty 长连接服务。
     */
    @Singleton
    NettyChatServer nettyChatServer(
        EventBus eventBus,
        SslContext sslContext,
        SessionResolver sessionResolver,
        UserChannelDirectory<Channel> userChannelDirectory,
        @Property(name = "mochat.netty.tcp.port") int tcpPort,
        @Property(name = "mochat.netty.tcp.frame.max-length") int maxFrameLength,
        @Property(name = "mochat.netty.tcp.heartbeat.interval") Duration heartbeatInterval,
        @Property(name = "mochat.netty.tcp.heartbeat.timeout") Duration heartbeatTimeout
    ) {
        // 配置文件用 Duration 表达更直观，这里再转换成 Netty 需要的秒数。
        int heartbeatIntervalSeconds = (int) Math.max(1L, heartbeatInterval.getSeconds());
        int heartbeatIdleTimeoutSeconds = (int) Math.max(1L, heartbeatTimeout.getSeconds());
        ChatChannelInitializer channelInitializer = new ChatChannelInitializer(
            eventBus,
            sslContext,
            sessionResolver,
            userChannelDirectory,
            maxFrameLength,
            heartbeatIntervalSeconds,
            heartbeatIdleTimeoutSeconds
        );
        return new NettyChatServer(tcpPort, channelInitializer);
    }

    /**
     * 创建 TLS 上下文，保证聊天 TCP 连接始终带加密。
     */
    @Singleton
    SslContext sslContext(
        @Property(name = "mochat.tls.enabled", defaultValue = "true") boolean tlsEnabled,
        @Property(name = "mochat.tls.certificate-path") String certificatePath,
        @Property(name = "mochat.tls.private-key-path") String privateKeyPath,
        @Property(name = "mochat.tls.self-signed", defaultValue = "false") boolean selfSigned
    ) {
        try {
            return buildMandatorySslContext(tlsEnabled, certificatePath, privateKeyPath, selfSigned);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build TLS context", exception);
        }
    }

    /**
     * 构建必须开启 TLS 的上下文。
     */
    static SslContext buildMandatorySslContext(
        boolean tlsEnabled,
        String certificatePath,
        String privateKeyPath,
        boolean selfSigned
    ) throws Exception {
        if (!tlsEnabled) {
            throw new IllegalStateException("TLS is mandatory for chat TCP connections; mochat.tls.enabled=false is not supported");
        }
        return buildSslContext(certificatePath, privateKeyPath, selfSigned);
    }

    /**
     * 按证书配置或自签证书选项创建 TLS 上下文。
     */
    static SslContext buildSslContext(String certificatePath, String privateKeyPath, boolean selfSigned) throws Exception {
        boolean hasCertificatePath = !certificatePath.isBlank();
        boolean hasPrivateKeyPath = !privateKeyPath.isBlank();
        if (hasCertificatePath != hasPrivateKeyPath) {
            throw new IllegalStateException("TLS certificate-path and private-key-path must both be configured together");
        }
        if (hasCertificatePath) {
            return NettyChatServer.buildTls13Context(new File(certificatePath), new File(privateKeyPath));
        }
        if (!selfSigned) {
            throw new IllegalStateException("TLS certificate-path and private-key-path are required when TLS is enabled and self-signed is disabled");
        }

        // 本地开发没准备正式证书时，用临时自签证书把链路先跑起来。
        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate("localhost");
        return NettyChatServer.buildTls13Context(selfSignedCertificate.certificate(), selfSignedCertificate.privateKey());
    }
}

/**
 * 应用启动时自动执行数据库迁移。
 */
@Singleton
@Context
@Requires(property = "mochat.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class FlywayMigrationBootstrap {
    private final Flyway flyway;

    /**
     * 注入要执行的 Flyway 实例。
     */
    FlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    /**
     * 在应用就绪前执行数据库升级。
     */
    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
