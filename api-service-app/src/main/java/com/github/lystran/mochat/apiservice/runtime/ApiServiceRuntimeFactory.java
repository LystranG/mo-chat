package com.github.lystran.mochat.apiservice.runtime;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
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
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

/**
 * 按配置创建 `api-service` 运行时要用到的基础组件。
 */
@Factory
public final class ApiServiceRuntimeFactory {
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L;
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

    /**
     * 在启用 PostgreSQL 依赖时创建数据源。
     */
    @Singleton
    @Requires(property = "mochat.api-service.dependencies.postgres-enabled", notEquals = "false", defaultValue = "true")
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
     * 创建 Flyway，用来管理 api-service 依赖的社交关系读写表结构。
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

    /**
     * 创建 api-service 用于用户、好友、群组等业务写入的全局 id 生成器。
     */
    @Singleton
    @Requires(missingBeans = IdGenerator.class)
    IdGenerator idGenerator(@Property(name = "mochat.api-service.id.worker-id", defaultValue = "2") long workerId) {
        return new ApiServiceSnowflakeIdGenerator(workerId);
    }

    private static final class ApiServiceSnowflakeIdGenerator implements IdGenerator {
        private final long workerId;
        private long lastTimestamp = -1L;
        private long sequence;

        ApiServiceSnowflakeIdGenerator(long workerId) {
            if (workerId < 0 || workerId > MAX_WORKER_ID) {
                throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
            }
            this.workerId = workerId;
        }

        @Override
        public synchronized long nextId() {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException("system clock moved backwards");
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0L) {
                    timestamp = waitForNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = timestamp;
            return ((timestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
        }

        private long waitForNextMillis(long previousTimestamp) {
            long timestamp = System.currentTimeMillis();
            while (timestamp <= previousTimestamp) {
                timestamp = System.currentTimeMillis();
            }
            return timestamp;
        }
    }
}

@Singleton
@Requires(bean = Flyway.class)
@Requires(property = "mochat.api-service.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class ApiServiceFlywayMigrationBootstrap {
    private final Flyway flyway;

    ApiServiceFlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
