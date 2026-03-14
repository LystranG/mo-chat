package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("mochat.access-gateway")
public class AccessGatewayServiceConfiguration {
    private final Tcp tcp = new Tcp();
    private final Tls tls = new Tls();
    private final Grpc grpc = new Grpc();
    private final Route route = new Route();
    private final Drain drain = new Drain();
    private final Dependencies dependencies = new Dependencies();

    public Tcp getTcp() {
        return tcp;
    }

    public Tls getTls() {
        return tls;
    }

    public Grpc getGrpc() {
        return grpc;
    }

    public Route getRoute() {
        return route;
    }

    public Drain getDrain() {
        return drain;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    @ConfigurationProperties("tcp")
    public static class Tcp {
        private boolean enabled = true;
        private String host = "0.0.0.0";
        private int port = 9000;
        private int frameMaxLength = 64 * 1024;
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration heartbeatTimeout = Duration.ofSeconds(60);
        private int sessionResolutionThreads = 4;
        private int sessionResolutionQueueCapacity = 128;
        private int sessionResolutionPendingLimit = 64;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getFrameMaxLength() {
            return frameMaxLength;
        }

        public void setFrameMaxLength(int frameMaxLength) {
            this.frameMaxLength = frameMaxLength;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getHeartbeatTimeout() {
            return heartbeatTimeout;
        }

        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }

        public int getSessionResolutionThreads() {
            return sessionResolutionThreads;
        }

        public void setSessionResolutionThreads(int sessionResolutionThreads) {
            this.sessionResolutionThreads = sessionResolutionThreads;
        }

        public int getSessionResolutionQueueCapacity() {
            return sessionResolutionQueueCapacity;
        }

        public void setSessionResolutionQueueCapacity(int sessionResolutionQueueCapacity) {
            this.sessionResolutionQueueCapacity = sessionResolutionQueueCapacity;
        }

        public int getSessionResolutionPendingLimit() {
            return sessionResolutionPendingLimit;
        }

        public void setSessionResolutionPendingLimit(int sessionResolutionPendingLimit) {
            this.sessionResolutionPendingLimit = sessionResolutionPendingLimit;
        }
    }

    @ConfigurationProperties("tls")
    public static class Tls {
        private boolean enabled = true;
        private boolean selfSigned = true;
        private String certificatePath = "";
        private String privateKeyPath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSelfSigned() {
            return selfSigned;
        }

        public void setSelfSigned(boolean selfSigned) {
            this.selfSigned = selfSigned;
        }

        public String getCertificatePath() {
            return certificatePath;
        }

        public void setCertificatePath(String certificatePath) {
            this.certificatePath = certificatePath;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }
    }

    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19093;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    @ConfigurationProperties("route")
    public static class Route {
        private String gatewayPod = "access-gateway-local";
        private Map<String, String> peerTargets = new LinkedHashMap<>();

        public String getGatewayPod() {
            return gatewayPod;
        }

        public void setGatewayPod(String gatewayPod) {
            this.gatewayPod = gatewayPod;
        }

        public Map<String, String> getPeerTargets() {
            return peerTargets;
        }

        public void setPeerTargets(Map<String, String> peerTargets) {
            this.peerTargets = peerTargets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(peerTargets);
        }
    }

    @ConfigurationProperties("drain")
    public static class Drain {
        private boolean enabled;
        private Duration gracePeriod = Duration.ofSeconds(30);
        private boolean shutdownWaitEnabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getGracePeriod() {
            return gracePeriod;
        }

        public void setGracePeriod(Duration gracePeriod) {
            this.gracePeriod = gracePeriod;
        }

        public boolean isShutdownWaitEnabled() {
            return shutdownWaitEnabled;
        }

        public void setShutdownWaitEnabled(boolean shutdownWaitEnabled) {
            this.shutdownWaitEnabled = shutdownWaitEnabled;
        }
    }

    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean apiGrpcEnabled = true;
        private boolean redisEnabled = true;

        public boolean isApiGrpcEnabled() {
            return apiGrpcEnabled;
        }

        public void setApiGrpcEnabled(boolean apiGrpcEnabled) {
            this.apiGrpcEnabled = apiGrpcEnabled;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }
    }
}
