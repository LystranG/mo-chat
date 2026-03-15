package com.github.lystran.mochat.apiservice.runtime;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.infra.redis.RedisEventBus;
import com.github.lystran.mochat.infra.redis.RedisOfflineQueue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * 按配置创建 `api-service` 运行时要用到的 Redis 相关组件。
 */
@Factory
public final class ApiServiceRuntimeFactory {
    /**
     * 在启用 Redis 依赖时创建 Redis 客户端。
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.api-service.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    /**
     * 创建普通的 Redis 读写连接。
     */
    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    /**
     * 创建 Redis 的发布订阅连接，给事件总线收发消息用。
     */
    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(RedisClient redisClient) {
        return redisClient.connectPubSub();
    }

    /**
     * 暴露同步 Redis 命令接口，给上层业务直接读写 Redis。
     */
    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }

    /**
     * 如果当前还没有事件总线实现，就创建一个基于 Redis 的实现。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(bean = StatefulRedisPubSubConnection.class)
    @Requires(missingBeans = EventBus.class)
    EventBus eventBus(
        RedisCommands<String, String> redisCommands,
        StatefulRedisPubSubConnection<String, String> redisPubSubConnection
    ) {
        return new RedisEventBus(redisCommands, redisPubSubConnection);
    }

    /**
     * 如果当前还没有离线队列实现，就创建一个基于 Redis 的实现。
     */
    @Singleton
    @Requires(bean = RedisCommands.class)
    @Requires(missingBeans = OfflineQueue.class)
    OfflineQueue offlineQueue(RedisCommands<String, String> redisCommands) {
        return new RedisOfflineQueue(redisCommands);
    }
}
