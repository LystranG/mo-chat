package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * 持久化服务的集中配置。
 */
@ConfigurationProperties("mochat.persistence-service")
public class PersistenceServiceConfiguration {
    private final Flyway flyway = new Flyway(); // 数据库表结构升级配置。
    private final Queue queue = new Queue(); // 消息入库消费者配置。
    private final Dependencies dependencies = new Dependencies(); // 外部依赖开关。

    /**
     * 返回 Flyway 配置。
     */
    public Flyway getFlyway() {
        return flyway;
    }

    /**
     * 返回消息队列消费配置。
     */
    public Queue getQueue() {
        return queue;
    }

    /**
     * 返回外部依赖开关配置。
     */
    public Dependencies getDependencies() {
        return dependencies;
    }

    /**
     * 描述数据库表结构升级相关配置。
     */
    @ConfigurationProperties("flyway")
    public static class Flyway {
        private boolean migrateOnStart = true;

        /**
         * 返回启动时是否自动执行数据库升级。
         */
        public boolean isMigrateOnStart() {
            return migrateOnStart;
        }

        /**
         * 更新启动时是否自动执行数据库升级。
         */
        public void setMigrateOnStart(boolean migrateOnStart) {
            this.migrateOnStart = migrateOnStart;
        }
    }

    /**
     * 描述消息入库消费者配置。
     */
    @ConfigurationProperties("queue")
    public static class Queue {
        private boolean consumerEnabled = true; // 是否真正启动入库消费者。

        /**
         * 返回是否开启入库消费者。
         */
        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        /**
         * 更新是否开启入库消费者。
         */
        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }
    }

    /**
     * 描述持久化服务依赖的外围组件。
     */
    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean postgresEnabled = true; // 是否依赖 PostgreSQL。
        private boolean redisEnabled = true; // 是否依赖 Redis。
        private boolean mqEnabled = true; // 是否依赖消息队列。

        /**
         * 返回是否开启 PostgreSQL 依赖。
         */
        public boolean isPostgresEnabled() {
            return postgresEnabled;
        }

        /**
         * 更新是否开启 PostgreSQL 依赖。
         */
        public void setPostgresEnabled(boolean postgresEnabled) {
            this.postgresEnabled = postgresEnabled;
        }

        /**
         * 返回是否开启 Redis 依赖。
         */
        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        /**
         * 更新是否开启 Redis 依赖。
         */
        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        /**
         * 返回是否开启消息队列依赖。
         */
        public boolean isMqEnabled() {
            return mqEnabled;
        }

        /**
         * 更新是否开启消息队列依赖。
         */
        public void setMqEnabled(boolean mqEnabled) {
            this.mqEnabled = mqEnabled;
        }
    }
}
