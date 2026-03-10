package com.github.lystran.mochat.runtime.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.api-service")
public class ApiServiceConfiguration {
    private final Http http = new Http();
    private final Grpc grpc = new Grpc();
    private final Dependencies dependencies = new Dependencies();

    public Http getHttp() {
        return http;
    }

    public Grpc getGrpc() {
        return grpc;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    @ConfigurationProperties("http")
    public static class Http {
        private String host = "0.0.0.0";
        private int port = 8080;

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

    @ConfigurationProperties("grpc")
    public static class Grpc {
        private int port = 19091;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    @ConfigurationProperties("dependencies")
    public static class Dependencies {
        private boolean redisEnabled = true;
        private boolean postgresEnabled = true;

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public boolean isPostgresEnabled() {
            return postgresEnabled;
        }

        public void setPostgresEnabled(boolean postgresEnabled) {
            this.postgresEnabled = postgresEnabled;
        }
    }
}
