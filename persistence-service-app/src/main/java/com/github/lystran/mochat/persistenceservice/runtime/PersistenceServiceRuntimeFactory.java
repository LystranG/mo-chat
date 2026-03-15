package com.github.lystran.mochat.persistenceservice.runtime;

import com.github.lystran.mochat.persistence.ConversationRepository;
import com.github.lystran.mochat.persistence.MessageRepository;
import com.github.lystran.mochat.persistence.MqConsumer;
import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

/**
 * 按配置创建持久化服务运行时要用到的数据库、Redis 和 RocketMQ 组件。
 */
@Factory
public final class PersistenceServiceRuntimeFactory {
    /**
     * 在没有现成数据源时，按配置创建 PostgreSQL 连接池入口。
     */
    @Singleton
    @Requires(property = "mochat.persistence-service.dependencies.postgres-enabled", notEquals = "false", defaultValue = "true")
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
     * 创建 Flyway，用来在启动时执行数据库迁移。
     */
    @Singleton
    @Requires(bean = DataSource.class)
    @Requires(missingBeans = Flyway.class)
    Flyway flyway(DataSource dataSource, @Property(name = "mochat.flyway.locations") String flywayLocations) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(flywayLocations)
            .load();
    }

    /**
     * 在启用 Redis 时创建 Lettuce 客户端。
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.persistence-service.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    @Requires(missingBeans = RedisClient.class)
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    /**
     * 创建持久化服务自己的 Redis 长连接。
     */
    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    @Requires(missingBeans = StatefulRedisConnection.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * 暴露同步 Redis 命令接口，给缓存和协调逻辑使用。
     */
    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    @Requires(missingBeans = RedisCommands.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    /**
     * 创建消息落库仓储。
     */
    @Singleton
    @Requires(missingBeans = MessageRepository.class)
    MessageRepository messageRepository() {
        return new MessageRepository();
    }

    /**
     * 创建会话进度落库仓储。
     */
    @Singleton
    @Requires(missingBeans = ConversationRepository.class)
    ConversationRepository conversationRepository() {
        return new ConversationRepository();
    }

    /**
     * 创建群消息最近几条的缓存组件。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(missingBeans = GroupMessageCache.class)
    GroupMessageCache groupMessageCache(RedisCommands<String, String> redisCommands) {
        return new GroupMessageCache(redisCommands);
    }

    /**
     * 创建真正执行“落库并推进会话进度”的入口。
     */
    @Singleton
    @Requires(bean = DataSource.class)
    @Requires(bean = MessageRepository.class)
    @Requires(bean = ConversationRepository.class)
    @Requires(bean = GroupMessageCache.class)
    @Requires(missingBeans = MqConsumer.class)
    MqConsumer mqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        return new MqConsumer(dataSource, messageRepository, conversationRepository, groupMessageCache);
    }

    /**
     * 创建 RocketMQ 顺序消费端，并订阅持久化主题。
     */
    @Singleton
    @Requires(property = "mochat.persistence-service.dependencies.mq-enabled", notEquals = "false", defaultValue = "true")
    @Requires(missingBeans = DefaultMQPushConsumer.class)
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
     * 把 RocketMQ 消费端和落库入口连起来。
     */
    @Singleton
    @Requires(bean = DefaultMQPushConsumer.class)
    @Requires(bean = MqConsumer.class)
    @Requires(missingBeans = RocketMqPersistenceConsumer.class)
    RocketMqPersistenceConsumer rocketMqPersistenceConsumer(
        DefaultMQPushConsumer defaultMqPushConsumer,
        MqConsumer mqConsumer
    ) {
        return new RocketMqPersistenceConsumer(defaultMqPushConsumer, mqConsumer);
    }
}
