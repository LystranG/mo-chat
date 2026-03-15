package com.github.lystran.mochat.runtime.topology;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一承接运行时拓扑配置，描述当前 Pod 身份和网关寻址方式。
 */
@ConfigurationProperties("mochat.runtime")
public class RuntimeTopologyConfiguration {
    private Pod pod = new Pod(); // 当前进程所在 Pod 的基础信息。
    private Gateway gateway = new Gateway(); // 网关身份和寻址规则。

    /**
     * 返回当前 Pod 配置。
     */
    public Pod getPod() {
        return pod;
    }

    /**
     * 更新当前 Pod 配置；传空时回到默认值。
     */
    public void setPod(Pod pod) {
        this.pod = pod == null ? new Pod() : pod;
    }

    /**
     * 返回网关拓扑配置。
     */
    public Gateway getGateway() {
        return gateway;
    }

    /**
     * 更新网关拓扑配置；传空时回到默认值。
     */
    public void setGateway(Gateway gateway) {
        this.gateway = gateway == null ? new Gateway() : gateway;
    }

    /**
     * 描述当前 Pod 自己的基础身份信息。
     */
    @ConfigurationProperties("pod")
    public static class Pod {
        private String name = ""; // 当前 Pod 名称，通常直接取 Kubernetes 下发的 POD_NAME。
        private String namespace = "default"; // 当前 Pod 所在命名空间。

        /**
         * 返回当前 Pod 名称。
         */
        public String getName() {
            return name;
        }

        /**
         * 更新当前 Pod 名称。
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 返回当前 Pod 所在命名空间。
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * 更新当前 Pod 所在命名空间。
         */
        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

    /**
     * 描述网关实例如何确认自己是谁，以及别人如何找到它。
     */
    @ConfigurationProperties("gateway")
    public static class Gateway {
        private GatewayIdentityMode identityMode = GatewayIdentityMode.CONFIGURED; // 当前网关名字从哪里来。
        private String identityValue = "access-gateway-local"; // 手工指定身份时使用的固定名字。
        private GatewayDiscoveryMode discoveryMode = GatewayDiscoveryMode.STATIC_MAP; // 其他服务怎么把网关名字换成可连地址。
        private Map<String, String> staticTargets = new LinkedHashMap<>(); // 本地开发或非 k8s 环境下的固定地址表。
        private String headlessService = "access-gateway-headless"; // 给每个网关 Pod 提供独立 DNS 的无头服务名。
        private String namespace = ""; // 不单独配置时，可回退为当前 Pod 命名空间。
        private String clusterDomain = "cluster.local"; // 集群内部服务域名后缀。
        private int grpcPort = 19093; // 服务间访问网关时使用的 gRPC 端口。

        /**
         * 返回网关身份来源。
         */
        public GatewayIdentityMode getIdentityMode() {
            return identityMode;
        }

        /**
         * 更新网关身份来源；传空时回到默认模式。
         */
        public void setIdentityMode(GatewayIdentityMode identityMode) {
            this.identityMode = identityMode == null ? GatewayIdentityMode.CONFIGURED : identityMode;
        }

        /**
         * 返回手工指定的网关名字。
         */
        public String getIdentityValue() {
            return identityValue;
        }

        /**
         * 更新手工指定的网关名字。
         */
        public void setIdentityValue(String identityValue) {
            this.identityValue = identityValue;
        }

        /**
         * 返回网关地址发现方式。
         */
        public GatewayDiscoveryMode getDiscoveryMode() {
            return discoveryMode;
        }

        /**
         * 更新网关地址发现方式；传空时回到默认模式。
         */
        public void setDiscoveryMode(GatewayDiscoveryMode discoveryMode) {
            this.discoveryMode = discoveryMode == null ? GatewayDiscoveryMode.STATIC_MAP : discoveryMode;
        }

        /**
         * 返回固定地址表。
         */
        public Map<String, String> getStaticTargets() {
            return staticTargets;
        }

        /**
         * 更新固定地址表，并复制一份避免外部再改动内部配置。
         */
        public void setStaticTargets(Map<String, String> staticTargets) {
            this.staticTargets = staticTargets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(staticTargets);
        }

        /**
         * 返回无头服务名。
         */
        public String getHeadlessService() {
            return headlessService;
        }

        /**
         * 更新无头服务名。
         */
        public void setHeadlessService(String headlessService) {
            this.headlessService = headlessService;
        }

        /**
         * 返回网关所在命名空间。
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * 更新网关所在命名空间。
         */
        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        /**
         * 返回集群服务域名后缀。
         */
        public String getClusterDomain() {
            return clusterDomain;
        }

        /**
         * 更新集群服务域名后缀。
         */
        public void setClusterDomain(String clusterDomain) {
            this.clusterDomain = clusterDomain;
        }

        /**
         * 返回网关 gRPC 端口。
         */
        public int getGrpcPort() {
            return grpcPort;
        }

        /**
         * 更新网关 gRPC 端口。
         */
        public void setGrpcPort(int grpcPort) {
            this.grpcPort = grpcPort;
        }
    }
}
