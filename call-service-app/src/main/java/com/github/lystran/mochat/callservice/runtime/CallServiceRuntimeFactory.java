package com.github.lystran.mochat.callservice.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;


@Factory
public final class CallServiceRuntimeFactory {
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L;//2024-01-01 00:00:00 UTC 的毫秒时间戳
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;


    @Singleton
    @Requires(property = "mochat.call-service.dependencies.postgres-enabled", notEquals = "false", defaultValue = "true")
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
    @Requires(property = "mochat.call-service.dependencies.redis-enabled", notEquals = "false", defaultValue = "true")
    @Requires(missingBeans = RedisClient.class)
    RedisClient redisClient(@Property(name = "mochat.redis.uri") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    @Requires(bean = RedisClient.class)
    @Requires(missingBeans = StatefulRedisConnection.class)
    StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }


    @Singleton
    @Requires(bean = StatefulRedisConnection.class)
    @Requires(missingBeans = RedisCommands.class)
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> redisConnection) {
        return redisConnection.sync();
    }


    @Singleton
    @Bean(preDestroy = "shutdown")
    @Requires(property = "mochat.call-service.dependencies.mq-enabled", notEquals = "false", defaultValue = "true")
    DefaultMQProducer defaultMqProducer(
        @Property(name = "mochat.rocketmq.name-server") String nameServer,
        @Property(name = "mochat.rocketmq.call-producer-group", defaultValue = "mochat-call-producer") String producerGroup
    ) {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        try {
            producer.start();
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start RocketMQ producer for call-service", exception);
        }
        return producer;
    }


    @Singleton
    @Requires(missingBeans = IdGenerator.class)
    IdGenerator idGenerator(@Property(name = "mochat.call-service.id.worker-id", defaultValue = "3") long workerId) {
        return new CallServiceSnowflakeIdGenerator(workerId);
    }

    private static final class CallServiceSnowflakeIdGenerator implements IdGenerator {
        private final long workerId;
        private long lastTimestamp = -1L;
        private long sequence;

        CallServiceSnowflakeIdGenerator(long workerId) {
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
