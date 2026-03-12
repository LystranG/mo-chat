package com.github.lystran.mochat.connection;

@FunctionalInterface
public interface GatewayDrainState {
    GatewayDrainState ACCEPTING = () -> false;

    boolean isDraining();

    static GatewayDrainState accepting() {
        return ACCEPTING;
    }
}
