package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.lock.JucConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
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
import java.util.Optional;

@Factory
public final class MochatRuntimeFactory {
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

    @Singleton
    Flyway flyway(DataSource dataSource, @Property(name = "mochat.flyway.locations") String flywayLocations) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(flywayLocations)
            .load();
    }

    @Singleton
    @Bean(preDestroy = "shutdown")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Singleton
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    @Singleton
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    @Singleton
    OfflineQueue offlineQueue(RedisCommands<String, String> redisCommands) {
        return new RedisOfflineQueue(redisCommands);
    }

    @Singleton
    ConversationLock conversationLock() {
        return new JucConversationLock();
    }

    @Singleton
    ConversationSeqGenerator conversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        ConversationStateRepository conversationStateRepository
    ) {
        return new RedisConversationSeqGenerator(
            redisCommands,
            conversationLock,
            conversationId -> conversationStateRepository.findConversationLatestState(conversationId)
                .map(ConversationStateRepository.ConversationLatestState::latestSeq)
                .orElse(0L)
        );
    }

    @Singleton
    IdempotencyStore idempotencyStore(RedisCommands<String, String> redisCommands) {
        return new RedisIdempotencyStore(redisCommands);
    }

    @Singleton
    UserChannelDirectory<Channel> userChannelDirectory() {
        return new InMemoryChannelDirectory<>();
    }

    @Singleton
    IdGenerator idGenerator(@Property(name = "mochat.id.worker-id", defaultValue = "1") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @Singleton
    MessageRepository messageRepository() {
        return new MessageRepository();
    }

    @Singleton
    ConversationRepository conversationRepository() {
        return new ConversationRepository();
    }

    @Singleton
    GroupMessageCache groupMessageCache(RedisCommands<String, String> redisCommands) {
        return new GroupMessageCache(redisCommands);
    }

    @Singleton
    MqConsumer mqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        return new MqConsumer(dataSource, messageRepository, conversationRepository, groupMessageCache);
    }

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

    @Singleton
    RocketMqPersistenceConsumer rocketMqPersistenceConsumer(
        DefaultMQPushConsumer defaultMqPushConsumer,
        MqConsumer mqConsumer
    ) {
        return new RocketMqPersistenceConsumer(defaultMqPushConsumer, mqConsumer);
    }

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

    @Singleton
    @Replaces(RocketMqProducer.class)
    RocketMqProducer rocketMqProducer(
        DefaultMQProducer defaultMqProducer,
        @Property(name = "mochat.rocketmq.topic") String topic
    ) {
        return new RocketMqProducer(defaultMqProducer, topic);
    }

    @Singleton
    OutboundEventSubscriber outboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        return new OutboundEventSubscriber(eventBus, userChannelDirectory, offlineQueue);
    }

    @Singleton
    NettyChatServer nettyChatServer(
        EventBus eventBus,
        Optional<SslContext> sslContext,
        @Property(name = "mochat.netty.tcp.port") int tcpPort,
        @Property(name = "mochat.netty.tcp.frame.max-length") int maxFrameLength,
        @Property(name = "mochat.netty.tcp.heartbeat.timeout") Duration heartbeatTimeout
    ) {
        int heartbeatIdleTimeoutSeconds = (int) Math.max(1L, heartbeatTimeout.getSeconds());
        ChatChannelInitializer channelInitializer = new ChatChannelInitializer(
            eventBus,
            sslContext.orElse(null),
            maxFrameLength,
            heartbeatIdleTimeoutSeconds
        );
        return new NettyChatServer(tcpPort, channelInitializer);
    }

    @Singleton
    @Requires(property = "mochat.tls.enabled", value = "true")
    SslContext sslContext(
        @Property(name = "mochat.tls.certificate-path") String certificatePath,
        @Property(name = "mochat.tls.private-key-path") String privateKeyPath
    ) {
        if (certificatePath.isBlank() || privateKeyPath.isBlank()) {
            throw new IllegalStateException("TLS certificate-path and private-key-path are required when TLS is enabled");
        }

        try {
            return NettyChatServer.buildTls13Context(new File(certificatePath), new File(privateKeyPath));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build TLS context", exception);
        }
    }
}

@Singleton
@Context
@Requires(property = "mochat.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class FlywayMigrationBootstrap {
    private final Flyway flyway;

    FlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
