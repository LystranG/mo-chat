package com.github.lystran.mochat;

import java.util.LinkedHashMap;
import java.util.Map;

final class AppTestSupport {
    static final String RUNTIME_ASSEMBLY_SPEC = "app-runtime-assembly";

    private AppTestSupport() {
    }

    static Map<String, Object> runtimeAssemblyContextProperties() {
        return Map.ofEntries(
            Map.entry("spec.name", RUNTIME_ASSEMBLY_SPEC),
            Map.entry("mochat.legacy.persistence.enabled", true),
            Map.entry("mochat.flyway.migrate-on-start", false),
            Map.entry("mochat.netty.tcp.enabled", false),
            Map.entry("mochat.rocketmq.consumer.enabled", false),
            Map.entry("mochat.postgres.url", "jdbc:postgresql://127.0.0.1:1/mochat"),
            Map.entry("mochat.postgres.username", "mochat"),
            Map.entry("mochat.postgres.password", "mochat"),
            Map.entry("mochat.redis.uri", "redis://127.0.0.1:1"),
            Map.entry("mochat.rocketmq.name-server", "127.0.0.1:9876")
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

    static Map<String, Object> runtimeAssemblyInboundConsumerCompatibilityProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(runtimeAssemblyContextProperties());
        properties.put("mochat.message-service.inbound-consumer.enabled", true);
        return Map.copyOf(properties);
    }

    static Map<String, Object> runtimeAssemblyServerProperties() {
        return Map.ofEntries(
            Map.entry("spec.name", RUNTIME_ASSEMBLY_SPEC),
            Map.entry("mochat.legacy.persistence.enabled", true),
            Map.entry("mochat.flyway.migrate-on-start", false),
            Map.entry("mochat.netty.tcp.enabled", false),
            Map.entry("mochat.rocketmq.consumer.enabled", false),
            Map.entry("mochat.postgres.url", "jdbc:postgresql://127.0.0.1:1/mochat"),
            Map.entry("mochat.postgres.username", "mochat"),
            Map.entry("mochat.postgres.password", "mochat"),
            Map.entry("mochat.redis.uri", "redis://127.0.0.1:1"),
            Map.entry("mochat.rocketmq.name-server", "127.0.0.1:9876"),
            Map.entry("micronaut.server.port", -1)
        );
    }
}
