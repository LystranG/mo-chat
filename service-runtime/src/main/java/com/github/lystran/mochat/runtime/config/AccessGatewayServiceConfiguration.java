package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.access-gateway")
public class AccessGatewayServiceConfiguration {
    private final Tcp tcp = new Tcp();
    private final Tls tls = new Tls();
    private final Dependencies dependencies = new Dependencies();

    public Tcp getTcp() {
        return tcp;
    }

    public Tls getTls() {
        return tls;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    @ConfigurationProperties("tcp")
    public static class Tcp {
        private boolean enabled = true;
        private String host = "0.0.0.0";
        private int port = 9000;

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
    }

    @ConfigurationProperties("tls")
    public static class Tls {
        private boolean enabled = true;
        private boolean selfSigned = true;

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
