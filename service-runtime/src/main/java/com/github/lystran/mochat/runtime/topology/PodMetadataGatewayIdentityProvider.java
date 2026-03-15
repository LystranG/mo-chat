package com.github.lystran.mochat.runtime.topology;

/**
 * 直接用 Kubernetes Pod 名字当网关身份名。
 */
public final class PodMetadataGatewayIdentityProvider implements GatewayIdentityProvider {
    private final String podName;

    /**
     * 用当前 Pod 名字创建身份提供器。
     */
    public PodMetadataGatewayIdentityProvider(String podName) {
        this.podName = StaticGatewayIdentityProvider.normalizeRequiredValue(podName, "podName");
    }

    /**
     * 返回当前 Pod 名字。
     */
    @Override
    public String currentGatewayPod() {
        return podName;
    }
}
