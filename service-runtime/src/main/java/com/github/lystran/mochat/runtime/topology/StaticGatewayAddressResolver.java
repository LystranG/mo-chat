package com.github.lystran.mochat.runtime.topology;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StaticGatewayAddressResolver implements GatewayAddressResolver {
    private final Map<String, String> staticTargets;

    public StaticGatewayAddressResolver(Map<String, String> staticTargets) {
        Objects.requireNonNull(staticTargets, "staticTargets");
        Map<String, String> normalizedTargets = new LinkedHashMap<>();
        staticTargets.forEach((gatewayPod, targetAddress) -> {
            if (gatewayPod != null && !gatewayPod.isBlank() && targetAddress != null && !targetAddress.isBlank()) {
                normalizedTargets.put(gatewayPod.trim(), targetAddress.trim());
            }
        });
        this.staticTargets = Map.copyOf(normalizedTargets);
    }

    @Override
    public String resolve(String gatewayPod) {
        if (gatewayPod == null || gatewayPod.isBlank()) {
            return null;
        }
        return staticTargets.get(gatewayPod.trim());
    }
}
