package com.github.lystran.mochat;

import java.util.Map;

final class AppTestSupport {
    static final String RUNTIME_ASSEMBLY_SPEC = "app-runtime-assembly";

    private AppTestSupport() {
    }

    static Map<String, Object> runtimeAssemblyContextProperties() {
        return Map.of(
            "spec.name", RUNTIME_ASSEMBLY_SPEC,
            "mochat.flyway.migrate-on-start", false,
            "mochat.netty.tcp.enabled", false,
            "mochat.rocketmq.consumer.enabled", false,
            "mochat.postgres.url", "jdbc:postgresql://127.0.0.1:1/mochat",
            "mochat.postgres.username", "mochat",
            "mochat.postgres.password", "mochat",
            "mochat.redis.uri", "redis://127.0.0.1:1",
            "mochat.rocketmq.name-server", "127.0.0.1:9876"
        );
    }

    static Map<String, Object> runtimeAssemblyDefaultConfigProperties() {
        return Map.of(
            "spec.name", RUNTIME_ASSEMBLY_SPEC,
            "mochat.flyway.migrate-on-start", false,
            "mochat.netty.tcp.enabled", false,
            "mochat.rocketmq.consumer.enabled", false,
            "micronaut.server.port", -1
        );
    }

    static Map<String, Object> runtimeAssemblyServerProperties() {
        return Map.of(
            "spec.name", RUNTIME_ASSEMBLY_SPEC,
            "mochat.flyway.migrate-on-start", false,
            "mochat.netty.tcp.enabled", false,
            "mochat.rocketmq.consumer.enabled", false,
            "mochat.postgres.url", "jdbc:postgresql://127.0.0.1:1/mochat",
            "mochat.postgres.username", "mochat",
            "mochat.postgres.password", "mochat",
            "mochat.redis.uri", "redis://127.0.0.1:1",
            "mochat.rocketmq.name-server", "127.0.0.1:9876",
            "micronaut.server.port", -1
        );
    }
}
