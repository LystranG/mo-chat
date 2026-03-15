package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * API 服务的集中配置。
 */
@ConfigurationProperties("mochat.api-service")
public class ApiServiceConfiguration {
    private final Http http = new Http(); // 对外 HTTP 接口配置。
    private final Grpc grpc = new Grpc(); // 对内 gRPC 入口配置。
    private final Dependencies dependencies = new Dependencies(); // 外部依赖开关。

    /**
     * 返回 HTTP 配置。
     */
    public Http getHttp() {
        return http;
    }

    /**
     * 返回 gRPC 配置。
     */
    public Grpc getGrpc() {
        return grpc;
    }

    /**
     * 返回外部依赖开关配置。
     */
    public Dependencies getDependencies() {
        return dependencies;
    }

    /**
     * 描述 API 服务对外暴露的 HTTP 入口。
     */
    @ConfigurationProperties("http")
    public static class Http {
        private String host = "0.0.0.0";
        private int port = 8080;

        /**
         * 返回 HTTP 监听地址。
         */
        public String getHost() {
            return host;
        }

        /**
         * 更新 HTTP 监听地址。
         */
        public void setHost(String host) {
            this.host = host;
        }

        /**
         * 返回 HTTP 监听端口。
         */
        public int getPort() {
            return port;
        }

        /**
         * 更新 HTTP 监听端口。
         */
        public void setPort(int port) {
            this.port = port;
        }
    }

    /**
     * 描述 API 服务对内暴露的 gRPC 入口。
     */
    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19091;

        /**
         * 返回 gRPC 监听端口。
         */
        public int getPort() {
            return port;
        }

        /**
         * 更新 gRPC 监听端口。
         */
        public void setPort(int port) {
            this.port = port;
        }
    }

    /**
     * 描述 API 服务依赖的外围组件。
     */
    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean redisEnabled = true; // 是否依赖 Redis。
        private boolean postgresEnabled = true; // 是否依赖 PostgreSQL。

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
    }
}
