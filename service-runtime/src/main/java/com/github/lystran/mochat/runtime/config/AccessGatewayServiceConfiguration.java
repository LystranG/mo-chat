package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访问网关服务的集中配置。
 */
@ConfigurationProperties("mochat.access-gateway")
public class AccessGatewayServiceConfiguration {
    private final Tcp tcp = new Tcp(); // TCP 长连接入口配置。
    private final Tls tls = new Tls(); // 证书与加密配置。
    private final Grpc grpc = new Grpc(); // 服务间 gRPC 入口配置。
    private final Route route = new Route(); // 网关之间互相转发时使用的路由信息。
    private final Drain drain = new Drain(); // 优雅下线配置。
    private final Dependencies dependencies = new Dependencies(); // 外部依赖开关。

    /**
     * 返回 TCP 长连接配置。
     */
    public Tcp getTcp() {
        return tcp;
    }

    /**
     * 返回 TLS 配置。
     */
    public Tls getTls() {
        return tls;
    }

    /**
     * 返回 gRPC 配置。
     */
    public Grpc getGrpc() {
        return grpc;
    }

    /**
     * 返回路由配置。
     */
    public Route getRoute() {
        return route;
    }

    /**
     * 返回优雅下线配置。
     */
    public Drain getDrain() {
        return drain;
    }

    /**
     * 返回外部依赖开关配置。
     */
    public Dependencies getDependencies() {
        return dependencies;
    }

    /**
     * 描述面向客户端的 TCP 长连接入口。
     */
    @ConfigurationProperties("tcp")
    public static class Tcp {
        private boolean enabled = true;
        private String host = "0.0.0.0";
        private int port = 9000;
        private int frameMaxLength = 64 * 1024;
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration heartbeatTimeout = Duration.ofSeconds(60);
        private int sessionResolutionThreads = 4; // 解析 session 的工作线程数。
        private int sessionResolutionQueueCapacity = 128; // 等待解析的连接请求最多排队多少个。
        private int sessionResolutionPendingLimit = 64; // 单个事件循环同时挂起的解析请求上限。

        /**
         * 返回是否开启 TCP 入口。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 更新是否开启 TCP 入口。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回 TCP 监听地址。
         */
        public String getHost() {
            return host;
        }

        /**
         * 更新 TCP 监听地址。
         */
        public void setHost(String host) {
            this.host = host;
        }

        /**
         * 返回 TCP 监听端口。
         */
        public int getPort() {
            return port;
        }

        /**
         * 更新 TCP 监听端口。
         */
        public void setPort(int port) {
            this.port = port;
        }

        /**
         * 返回单帧最大长度。
         */
        public int getFrameMaxLength() {
            return frameMaxLength;
        }

        /**
         * 更新单帧最大长度。
         */
        public void setFrameMaxLength(int frameMaxLength) {
            this.frameMaxLength = frameMaxLength;
        }

        /**
         * 返回心跳发送间隔。
         */
        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        /**
         * 更新心跳发送间隔。
         */
        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        /**
         * 返回多长时间没动静就判定连接超时。
         */
        public Duration getHeartbeatTimeout() {
            return heartbeatTimeout;
        }

        /**
         * 更新连接超时阈值。
         */
        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }

        /**
         * 返回 session 解析线程数。
         */
        public int getSessionResolutionThreads() {
            return sessionResolutionThreads;
        }

        /**
         * 更新 session 解析线程数。
         */
        public void setSessionResolutionThreads(int sessionResolutionThreads) {
            this.sessionResolutionThreads = sessionResolutionThreads;
        }

        /**
         * 返回 session 解析排队上限。
         */
        public int getSessionResolutionQueueCapacity() {
            return sessionResolutionQueueCapacity;
        }

        /**
         * 更新 session 解析排队上限。
         */
        public void setSessionResolutionQueueCapacity(int sessionResolutionQueueCapacity) {
            this.sessionResolutionQueueCapacity = sessionResolutionQueueCapacity;
        }

        /**
         * 返回单个事件循环允许挂起的解析请求上限。
         */
        public int getSessionResolutionPendingLimit() {
            return sessionResolutionPendingLimit;
        }

        /**
         * 更新单个事件循环允许挂起的解析请求上限。
         */
        public void setSessionResolutionPendingLimit(int sessionResolutionPendingLimit) {
            this.sessionResolutionPendingLimit = sessionResolutionPendingLimit;
        }
    }

    /**
     * 描述网关 TCP 入口的证书配置。
     */
    @ConfigurationProperties("tls")
    public static class Tls {
        private boolean enabled = true;
        private boolean selfSigned = true; // 没有正式证书时，是否允许临时生成自签证书。
        private String certificatePath = "";
        private String privateKeyPath = "";

        /**
         * 返回是否开启 TLS。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 更新是否开启 TLS。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回是否允许自签证书。
         */
        public boolean isSelfSigned() {
            return selfSigned;
        }

        /**
         * 更新是否允许自签证书。
         */
        public void setSelfSigned(boolean selfSigned) {
            this.selfSigned = selfSigned;
        }

        /**
         * 返回证书文件路径。
         */
        public String getCertificatePath() {
            return certificatePath;
        }

        /**
         * 更新证书文件路径。
         */
        public void setCertificatePath(String certificatePath) {
            this.certificatePath = certificatePath;
        }

        /**
         * 返回私钥文件路径。
         */
        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        /**
         * 更新私钥文件路径。
         */
        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }
    }

    /**
     * 描述网关对内暴露的 gRPC 入口。
     */
    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19093;

        /**
         * 返回 gRPC 端口。
         */
        public int getPort() {
            return port;
        }

        /**
         * 更新 gRPC 端口。
         */
        public void setPort(int port) {
            this.port = port;
        }
    }

    /**
     * 描述网关之间互相转发消息时的路由信息。
     */
    @ConfigurationProperties("route")
    public static class Route {
        private String gatewayPod = "access-gateway-local"; // 当前网关对外声明的名字。
        private Map<String, String> peerTargets = new LinkedHashMap<>(); // 其他网关名字到地址的固定映射。

        /**
         * 返回当前网关名字。
         */
        public String getGatewayPod() {
            return gatewayPod;
        }

        /**
         * 更新当前网关名字。
         */
        public void setGatewayPod(String gatewayPod) {
            this.gatewayPod = gatewayPod;
        }

        /**
         * 返回固定路由表。
         */
        public Map<String, String> getPeerTargets() {
            return peerTargets;
        }

        /**
         * 更新固定路由表，并复制一份避免外部继续改动。
         */
        public void setPeerTargets(Map<String, String> peerTargets) {
            this.peerTargets = peerTargets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(peerTargets);
        }
    }

    /**
     * 描述网关优雅下线时的等待策略。
     */
    @ConfigurationProperties("drain")
    public static class Drain {
        private boolean enabled; // 是否在下线前先停止接新连接、等待旧连接迁走。
        private Duration gracePeriod = Duration.ofSeconds(30); // 给旧连接和转发请求留下的缓冲时间。
        private boolean shutdownWaitEnabled; // 关闭进程时是否额外等待清空中的任务。

        /**
         * 返回是否开启优雅下线。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 更新是否开启优雅下线。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回优雅下线等待时间。
         */
        public Duration getGracePeriod() {
            return gracePeriod;
        }

        /**
         * 更新优雅下线等待时间。
         */
        public void setGracePeriod(Duration gracePeriod) {
            this.gracePeriod = gracePeriod;
        }

        /**
         * 返回关闭进程时是否等待在途任务。
         */
        public boolean isShutdownWaitEnabled() {
            return shutdownWaitEnabled;
        }

        /**
         * 更新关闭进程时是否等待在途任务。
         */
        public void setShutdownWaitEnabled(boolean shutdownWaitEnabled) {
            this.shutdownWaitEnabled = shutdownWaitEnabled;
        }
    }

    /**
     * 描述访问网关依赖哪些外围能力。
     */
    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean apiGrpcEnabled = true; // 是否依赖 API 服务的 gRPC 能力。
        private boolean redisEnabled = true; // 是否依赖 Redis 做事件和会话辅助存储。

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
}
