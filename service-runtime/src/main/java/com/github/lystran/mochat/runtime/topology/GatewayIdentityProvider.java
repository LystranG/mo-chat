package com.github.lystran.mochat.runtime.topology;

@FunctionalInterface
public interface GatewayIdentityProvider {
    String currentGatewayPod();
}
