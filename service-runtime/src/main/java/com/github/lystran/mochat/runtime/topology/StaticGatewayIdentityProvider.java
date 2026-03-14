package com.github.lystran.mochat.runtime.topology;

public final class StaticGatewayIdentityProvider implements GatewayIdentityProvider {
    private final String gatewayPod;

    public StaticGatewayIdentityProvider(String gatewayPod) {
        this.gatewayPod = normalizeRequiredValue(gatewayPod, "gatewayPod");
    }

    @Override
    public String currentGatewayPod() {
        return gatewayPod;
    }

    static String normalizeRequiredValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.trim();
    }
}
