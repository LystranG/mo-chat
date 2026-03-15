package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * 消息服务的集中配置。
 */
@ConfigurationProperties("mochat.message-service")
public class MessageServiceConfiguration {
    private final Grpc grpc = new Grpc(); // 对内 gRPC 入口配置。
    private final Id id = new Id(); // 全局 id 生成配置。
    private final InboundConsumer inboundConsumer = new InboundConsumer(); // 消息入口消费者配置。
    private final Dependencies dependencies = new Dependencies(); // 外部依赖开关。
    private final Route route = new Route(); // 发往网关时使用的固定路由表。

    /**
     * 返回 gRPC 配置。
     */
    public Grpc getGrpc() {
        return grpc;
    }

    /**
     * 返回 id 生成配置。
     */
    public Id getId() {
        return id;
    }

    /**
     * 返回消息入口消费者配置。
     */
    public InboundConsumer getInboundConsumer() {
        return inboundConsumer;
    }

    /**
     * 返回外部依赖开关配置。
     */
    public Dependencies getDependencies() {
        return dependencies;
    }

    /**
     * 返回路由配置。
     */
    public Route getRoute() {
        return route;
    }

    /**
     * 描述消息服务对内暴露的 gRPC 入口。
     */
    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19092;

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
     * 描述消息 id 的节点编号配置。
     */
    @ConfigurationProperties("id")
    public static class Id {
        private long workerId = 1L; // 多实例部署时，用它区分不同发号节点。

        /**
         * 返回节点编号。
         */
        public long getWorkerId() {
            return workerId;
        }

        /**
         * 更新节点编号。
         */
        public void setWorkerId(long workerId) {
            this.workerId = workerId;
        }
    }

    /**
     * 描述消息入口消费者的开关。
     */
    @ConfigurationProperties("inbound-consumer")
    public static class InboundConsumer {
        private boolean enabled = true;

        /**
         * 返回是否开启消息入口消费者。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 更新是否开启消息入口消费者。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 描述消息服务依赖的外围组件。
     */
    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean apiGrpcEnabled = true; // 是否依赖 API 服务的 gRPC 能力。
        private boolean gatewayGrpcEnabled = true; // 是否依赖访问网关的 gRPC 能力。
        private boolean mqEnabled = true; // 是否依赖消息队列。
        private boolean redisEnabled = true; // 是否依赖 Redis。

        /**
         * 返回是否开启 API gRPC 依赖。
         */
        public boolean isApiGrpcEnabled() {
            return apiGrpcEnabled;
        }

        /**
         * 更新是否开启 API gRPC 依赖。
         */
        public void setApiGrpcEnabled(boolean apiGrpcEnabled) {
            this.apiGrpcEnabled = apiGrpcEnabled;
        }

        /**
         * 返回是否开启网关 gRPC 依赖。
         */
        public boolean isGatewayGrpcEnabled() {
            return gatewayGrpcEnabled;
        }

        /**
         * 更新是否开启网关 gRPC 依赖。
         */
        public void setGatewayGrpcEnabled(boolean gatewayGrpcEnabled) {
            this.gatewayGrpcEnabled = gatewayGrpcEnabled;
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
    }

    /**
     * 描述消息服务发往各网关实例时使用的固定地址表。
     */
    @ConfigurationProperties("route")
    public static class Route {
        private java.util.Map<String, String> gatewayTargets = new java.util.LinkedHashMap<>(); // key 是网关名字，value 是可直接连接的地址。

        /**
         * 返回网关地址表。
         */
        public java.util.Map<String, String> getGatewayTargets() {
            return gatewayTargets;
        }

        /**
         * 更新网关地址表，并复制一份避免外部继续改动。
         */
        public void setGatewayTargets(java.util.Map<String, String> gatewayTargets) {
            this.gatewayTargets = gatewayTargets == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(gatewayTargets);
        }
    }
}
