package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.message-service")
public class MessageServiceConfiguration {
    private final Grpc grpc = new Grpc();
    private final Id id = new Id();
    private final InboundConsumer inboundConsumer = new InboundConsumer();
    private final Dependencies dependencies = new Dependencies();
    private final Route route = new Route();

    public Grpc getGrpc() {
        return grpc;
    }

    public Id getId() {
        return id;
    }

    public InboundConsumer getInboundConsumer() {
        return inboundConsumer;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    public Route getRoute() {
        return route;
    }

    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19092;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    @ConfigurationProperties("id")
    public static class Id {
        private long workerId = 1L;

        public long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(long workerId) {
            this.workerId = workerId;
        }
    }

    @ConfigurationProperties("inbound-consumer")
    public static class InboundConsumer {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean apiGrpcEnabled = true;
        private boolean gatewayGrpcEnabled = true;
        private boolean mqEnabled = true;
        private boolean redisEnabled = true;

        public boolean isApiGrpcEnabled() {
            return apiGrpcEnabled;
        }

        public void setApiGrpcEnabled(boolean apiGrpcEnabled) {
            this.apiGrpcEnabled = apiGrpcEnabled;
        }

        public boolean isGatewayGrpcEnabled() {
            return gatewayGrpcEnabled;
        }

        public void setGatewayGrpcEnabled(boolean gatewayGrpcEnabled) {
            this.gatewayGrpcEnabled = gatewayGrpcEnabled;
        }

        public boolean isMqEnabled() {
            return mqEnabled;
        }

        public void setMqEnabled(boolean mqEnabled) {
            this.mqEnabled = mqEnabled;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }
    }

    @ConfigurationProperties("route")
    public static class Route {
        private java.util.Map<String, String> gatewayTargets = new java.util.LinkedHashMap<>();

        public java.util.Map<String, String> getGatewayTargets() {
            return gatewayTargets;
        }

        public void setGatewayTargets(java.util.Map<String, String> gatewayTargets) {
            this.gatewayTargets = gatewayTargets == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(gatewayTargets);
        }
    }
}
