package com.github.lystran.mochat.runtime.topology;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeTopologyConfigurationTest {
    @Test
    void bindsGatewayTopologyHierarchy() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.runtime.pod.name", "access-gateway-0",
            "mochat.runtime.pod.namespace", "chat",
            "mochat.runtime.gateway.identity-mode", "POD_METADATA",
            "mochat.runtime.gateway.discovery-mode", "KUBERNETES_DNS",
            "mochat.runtime.gateway.headless-service", "access-gateway-headless",
            "mochat.runtime.gateway.cluster-domain", "cluster.local",
            "mochat.runtime.gateway.grpc-port", 19093
        ))) {
            RuntimeTopologyConfiguration configuration = context.getBean(RuntimeTopologyConfiguration.class);

            assertEquals("access-gateway-0", configuration.getPod().getName());
            assertEquals("chat", configuration.getPod().getNamespace());
            assertEquals(GatewayIdentityMode.POD_METADATA, configuration.getGateway().getIdentityMode());
            assertEquals(GatewayDiscoveryMode.KUBERNETES_DNS, configuration.getGateway().getDiscoveryMode());
            assertEquals("access-gateway-headless", configuration.getGateway().getHeadlessService());
            assertEquals("cluster.local", configuration.getGateway().getClusterDomain());
            assertEquals(19093, configuration.getGateway().getGrpcPort());
        }
    }

    @Test
    void kubernetesDnsResolverBuildsHeadlessServicePodTarget() {
        GatewayAddressResolver resolver = new KubernetesDnsGatewayAddressResolver(
            "access-gateway-headless",
            "chat",
            "cluster.local",
            19093
        );

        assertEquals(
            "dns:///access-gateway-0.access-gateway-headless.chat.svc.cluster.local:19093",
            resolver.resolve("access-gateway-0")
        );
    }

    @Test
    void staticResolverReturnsMappedAddressOrNull() {
        GatewayAddressResolver resolver = new StaticGatewayAddressResolver(Map.of(
            "gateway-a", "127.0.0.1:19093"
        ));

        assertEquals("127.0.0.1:19093", resolver.resolve("gateway-a"));
        assertNull(resolver.resolve("gateway-b"));
    }

    @Test
    void podMetadataIdentityUsesDerivedPodName() {
        GatewayIdentityProvider identityProvider = new PodMetadataGatewayIdentityProvider("access-gateway-0");

        assertEquals("access-gateway-0", identityProvider.currentGatewayPod());
    }
}
