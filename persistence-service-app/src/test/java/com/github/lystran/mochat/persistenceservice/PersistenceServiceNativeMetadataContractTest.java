package com.github.lystran.mochat.persistenceservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceServiceNativeMetadataContractTest {
    @Test
    void bundlesRocketMqNativeReachabilityMetadata() throws IOException {
        Path metadata = repositoryRoot()
            .resolve("persistence-service-app")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("META-INF")
            .resolve("native-image")
            .resolve("com.github.lystran")
            .resolve("mochat")
            .resolve("reachability-metadata.json");
        assertTrue(Files.exists(metadata), "missing persistence-service reachability metadata");

        String metadataText = Files.readString(metadata);
        assertTrue(metadataText.contains("\"type\": \"org.apache.rocketmq.remoting.protocol.RemotingCommand\""));
        assertTrue(metadataText.contains("\"type\": \"org.apache.rocketmq.remoting.netty.NettyRemotingClient$NettyConnectManageHandler\""));
        assertTrue(metadataText.contains("\"type\": \"com.alibaba.fastjson.parser.deserializer.FastjsonASMDeserializer_1_RemotingCommand\""));
        assertTrue(metadataText.contains("\"type\": \"io.github.aliyunmq.logback.extensions.CustomConsoleAppender\""));
        assertTrue(metadataText.contains("\"type\": \"io.github.aliyunmq.logback.extensions.ProcessIdConverter\""));
        assertTrue(metadataText.contains("\"glob\": \"META-INF/services/org.apache.rocketmq.common.namesrv.TopAddressing\""));
        assertTrue(metadataText.contains("\"glob\": \"rmq.client.logback.xml\""));
        assertTrue(metadataText.contains("\"glob\": \"fastjson.properties\""));
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current;
    }
}
