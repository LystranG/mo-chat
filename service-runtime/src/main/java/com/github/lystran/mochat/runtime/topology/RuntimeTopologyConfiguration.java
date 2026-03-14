package com.github.lystran.mochat.runtime.topology;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("mochat.runtime")
public class RuntimeTopologyConfiguration {
    private Pod pod = new Pod();
    private Gateway gateway = new Gateway();

    public Pod getPod() {
        return pod;
    }

    public void setPod(Pod pod) {
        this.pod = pod == null ? new Pod() : pod;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway == null ? new Gateway() : gateway;
    }

    @ConfigurationProperties("pod")
    public static class Pod {
        private String name = "";
        private String namespace = "default";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

    @ConfigurationProperties("gateway")
    public static class Gateway {
        private GatewayIdentityMode identityMode = GatewayIdentityMode.CONFIGURED;
        private String identityValue = "access-gateway-local";
        private GatewayDiscoveryMode discoveryMode = GatewayDiscoveryMode.STATIC_MAP;
        private Map<String, String> staticTargets = new LinkedHashMap<>();
        private String headlessService = "access-gateway-headless";
        private String namespace = "";
        private String clusterDomain = "cluster.local";
        private int grpcPort = 19093;

        public GatewayIdentityMode getIdentityMode() {
            return identityMode;
        }

        public void setIdentityMode(GatewayIdentityMode identityMode) {
            this.identityMode = identityMode == null ? GatewayIdentityMode.CONFIGURED : identityMode;
        }

        public String getIdentityValue() {
            return identityValue;
        }

        public void setIdentityValue(String identityValue) {
            this.identityValue = identityValue;
        }

        public GatewayDiscoveryMode getDiscoveryMode() {
            return discoveryMode;
        }

        public void setDiscoveryMode(GatewayDiscoveryMode discoveryMode) {
            this.discoveryMode = discoveryMode == null ? GatewayDiscoveryMode.STATIC_MAP : discoveryMode;
        }

        public Map<String, String> getStaticTargets() {
            return staticTargets;
        }

        public void setStaticTargets(Map<String, String> staticTargets) {
            this.staticTargets = staticTargets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(staticTargets);
        }

        public String getHeadlessService() {
            return headlessService;
        }

        public void setHeadlessService(String headlessService) {
            this.headlessService = headlessService;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getClusterDomain() {
            return clusterDomain;
        }

        public void setClusterDomain(String clusterDomain) {
            this.clusterDomain = clusterDomain;
        }

        public int getGrpcPort() {
            return grpcPort;
        }

        public void setGrpcPort(int grpcPort) {
            this.grpcPort = grpcPort;
        }
    }
}
