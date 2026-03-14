package com.github.lystran.mochat.runtime.topology;

public final class KubernetesDnsGatewayAddressResolver implements GatewayAddressResolver {
    private final String headlessService;
    private final String namespace;
    private final String clusterDomain;
    private final int grpcPort;

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

    @Override
    public String resolve(String gatewayPod) {
        String normalizedGatewayPod = normalizeDnsSegment(gatewayPod, "gatewayPod");
        return "dns:///" + normalizedGatewayPod
            + "." + headlessService
            + "." + namespace
            + ".svc." + clusterDomain
            + ":" + grpcPort;
    }

    private static String normalizeClusterDomain(String clusterDomain) {
        String normalized = normalizeDnsSegment(clusterDomain, "clusterDomain");
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

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
