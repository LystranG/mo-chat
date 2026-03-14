package com.github.lystran.mochat.accessgateway.runtime;

import jakarta.inject.Singleton;

@Singleton
public final class GatewayIngressLifecycle {
    private final GatewayDrainManager gatewayDrainManager;

    public GatewayIngressLifecycle(GatewayDrainManager gatewayDrainManager) {
        this.gatewayDrainManager = gatewayDrainManager;
    }

    public boolean isLive() {
        return true;
    }

    public boolean isReady() {
        return !gatewayDrainManager.isDraining();
    }

    public void startDrain() {
        gatewayDrainManager.startDrain();
    }
}
