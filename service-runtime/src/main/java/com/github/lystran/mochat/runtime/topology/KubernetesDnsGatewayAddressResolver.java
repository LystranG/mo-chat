package com.github.lystran.mochat.runtime.topology;

/**
 * 按 Kubernetes 里的 Pod DNS 规则，拼出访问网关实例的地址。
 */
public final class KubernetesDnsGatewayAddressResolver implements GatewayAddressResolver {
    private final String headlessService; // 无头服务名，能让每个网关 Pod 都有自己的 DNS 名字。
    private final String namespace; // 网关所在命名空间。
    private final String clusterDomain; // 集群内部域名后缀，通常是 cluster.local。
    private final int grpcPort; // 其他服务连网关时使用的 gRPC 端口。

    /**
     * 组装根据 Pod DNS 找网关地址所需的固定参数。
     */
    public KubernetesDnsGatewayAddressResolver(
        String headlessService,
        String namespace,
        String clusterDomain,
        int grpcPort
    ) {
        this.headlessService = normalizeDnsSegment(headlessService, "headlessService");
        this.namespace = normalizeDnsSegment(namespace, "namespace");
        this.clusterDomain = normalizeClusterDomain(clusterDomain);
        if (grpcPort <= 0) {
            throw new IllegalArgumentException("grpcPort must be positive");
        }
        this.grpcPort = grpcPort;
    }

    /**
     * 把网关实例名转成可直接拨号的 gRPC 地址。
     */
    @Override
    public String resolve(String gatewayPod) {
        String normalizedGatewayPod = normalizeDnsSegment(gatewayPod, "gatewayPod");
        // 这里拼的是 Pod 专属 DNS：<pod>.<headless-service>.<namespace>.svc.<cluster-domain>
        return "dns:///" + normalizedGatewayPod
            + "." + headlessService
            + "." + namespace
            + ".svc." + clusterDomain
            + ":" + grpcPort;
    }

    /**
     * 规范化集群域名，去掉多余的点号。
     */
    private static String normalizeClusterDomain(String clusterDomain) {
        String normalized = normalizeDnsSegment(clusterDomain, "clusterDomain");
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 规范化单段 DNS 文本，拦住空值并去掉首尾点号。
     */
    private static String normalizeDnsSegment(String value, String propertyName) {
        String normalized = StaticGatewayIdentityProvider.normalizeRequiredValue(value, propertyName);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return StaticGatewayIdentityProvider.normalizeRequiredValue(normalized, propertyName);
    }
}
