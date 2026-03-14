package com.github.lystran.mochat.runtime.topology;

public final class PodMetadataGatewayIdentityProvider implements GatewayIdentityProvider {
    private final String podName;

    public PodMetadataGatewayIdentityProvider(String podName) {
        this.podName = StaticGatewayIdentityProvider.normalizeRequiredValue(podName, "podName");
    }

    @Override
    public String currentGatewayPod() {
        return podName;
    }
}
