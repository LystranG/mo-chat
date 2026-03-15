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
 * 统一创建数据库、Redis、MQ、TCP 服务这些运行时组件，并交给 Micronaut 管理。
 */
@Factory
public final class MochatRuntimeFactory {
    /**
     * 在外面没有提供数据源时，按配置创建默认的 PostgreSQL 数据源。
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
     * 创建 Flyway，供启动时跑数据库迁移。
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
     * 创建平时读写 Redis 用的连接。
     */
    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * 拿到同步方式的 Redis 命令入口，给上层直接调用。
     */
    @Singleton
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    /**
     * 创建 Redis 的发布订阅连接，专门给事件总线收发消息。
     */
    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    /**
     * 创建基于 Redis 发布订阅的事件总线。
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
     * 创建“同一个会话里的关键操作要排队执行”的锁。
     */
    @Singleton
    ConversationLock conversationLock() {
        return new JucConversationLock();
    }

    /**
     * 创建会话顺序号生成器；Redis 里没记录时，先从数据库最新顺序号接着往下算。
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
            // Redis 里这条会话的顺序号第一次出现，或中途丢了时，
            // 先用数据库里的最新顺序号接上，避免服务重启后顺序号往回跳。
            conversationId -> conversationStateRepository.findConversationLatestState(conversationId)
                .map(ConversationStateRepository.ConversationLatestState::latestSeq)
                .orElse(0L)
        );
    }

    /**
     * 创建发送去重存储，避免客户端重试时重复落消息。
     */
    @Singleton
    IdempotencyStore idempotencyStore(RedisCommands<String, String> redisCommands) {
        return new RedisIdempotencyStore(redisCommands);
    }

    /**
     * 创建“在线用户对应哪条连接”的内存目录。
     */
    @Singleton
    UserChannelDirectory<Channel> userChannelDirectory() {
        return new InMemoryChannelDirectory<>();
    }

    /**
     * 按配置创建全局 ID 生成器。
     */
    @Singleton
    IdGenerator idGenerator(@Property(name = "mochat.id.worker-id", defaultValue = "1") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    /**
     * 创建负责把消息写进数据库的对象。
     */
    @Singleton
    MessageRepository messageRepository() {
        return new MessageRepository();
    }

    /**
     * 创建负责更新会话状态的对象。
     */
    @Singleton
    ConversationRepository conversationRepository() {
        return new ConversationRepository();
    }

    /**
     * 创建群消息缓存。
     */
    @Singleton
    GroupMessageCache groupMessageCache(RedisCommands<String, String> redisCommands) {
        return new GroupMessageCache(redisCommands);
    }

    /**
     * 创建“从 MQ 拿到消息后，真正写库”的执行器。
     */
    @Singleton
    MqConsumer mqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        return new MqConsumer(dataSource, messageRepository, conversationRepository, groupMessageCache);
    }

    /**
     * 创建并配置 RocketMQ 消费者，但真正什么时候启动、关闭由别的启动类控制。
     */
    @Singleton
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
     * 创建持久化这条 MQ 消费链路的适配器。
     */
    @Singleton
    RocketMqPersistenceConsumer rocketMqPersistenceConsumer(
        DefaultMQPushConsumer defaultMqPushConsumer,
        MqConsumer mqConsumer
    ) {
        return new RocketMqPersistenceConsumer(defaultMqPushConsumer, mqConsumer);
    }

    /**
     * 创建并启动逻辑层发消息要用的 RocketMQ 生产者。
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
     * 用真的 RocketMQ 发送器替掉默认占位实现。
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
     * 创建订阅连接层出站事件的组件，把准备发给客户端的数据接过来。
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
     * 创建 Netty 聊天 TCP 服务，并把配置里的心跳、帧长这些值整理成连接层能直接用的参数。
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
        // 心跳时间最后是按秒传给 Netty 处理器的，这里顺手兜底成最少 1 秒，避免出现 0 或负数。
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
     * 按 TLS 配置创建聊天 TCP 要用的证书上下文。
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
     * 明确规定聊天 TCP 必须走 TLS，不允许退回明文连接。
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
     * 根据证书路径或自签名开关，真正把 TLS 所需的证书上下文建出来。
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

        // 只有明确允许时才临时生成自签名证书，避免生产环境不小心拿临时证书启动。
        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate("localhost");
        return NettyChatServer.buildTls13Context(selfSignedCertificate.certificate(), selfSignedCertificate.privateKey());
    }
}

/**
 * 应用启动时按配置跑数据库迁移的启动钩子。
 */
@Singleton
@Context
@Requires(property = "mochat.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class FlywayMigrationBootstrap {
    private final Flyway flyway;

    /**
     * 收下启动时要调用的 Flyway 对象。
     */
    FlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    /**
     * 在这个启动钩子初始化完成后，立刻执行数据库迁移。
     */
    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
