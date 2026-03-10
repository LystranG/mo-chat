package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.persistence-service")
public class PersistenceServiceConfiguration {
    private final Flyway flyway = new Flyway();
    private final Queue queue = new Queue();
    private final Dependencies dependencies = new Dependencies();

    public Flyway getFlyway() {
        return flyway;
    }

    public Queue getQueue() {
        return queue;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    @ConfigurationProperties("flyway")
    public static class Flyway {
        private boolean migrateOnStart = true;

        public boolean isMigrateOnStart() {
            return migrateOnStart;
        }

        public void setMigrateOnStart(boolean migrateOnStart) {
            this.migrateOnStart = migrateOnStart;
        }
    }

    @ConfigurationProperties("queue")
    public static class Queue {
        private boolean consumerEnabled = true;

        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }
    }

    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean postgresEnabled = true;
        private boolean redisEnabled = true;
        private boolean mqEnabled = true;

        public boolean isPostgresEnabled() {
            return postgresEnabled;
        }

        public void setPostgresEnabled(boolean postgresEnabled) {
            this.postgresEnabled = postgresEnabled;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public boolean isMqEnabled() {
            return mqEnabled;
        }

        public void setMqEnabled(boolean mqEnabled) {
            this.mqEnabled = mqEnabled;
        }
    }
}
