package com.github.lystran.mochat.runtime.topology;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 从一张固定地址表里查目标网关地址。
 */
public final class StaticGatewayAddressResolver implements GatewayAddressResolver {
    private final Map<String, String> staticTargets;

    /**
     * 收下固定地址表，并顺手清理空键空值。
     */
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

    /**
     * 从固定表里查这个网关身份名对应的地址。
     */
    @Override
    public String resolve(String gatewayPod) {
        if (gatewayPod == null || gatewayPod.isBlank()) {
            return null;
        }
        return staticTargets.get(gatewayPod.trim());
    }
}
