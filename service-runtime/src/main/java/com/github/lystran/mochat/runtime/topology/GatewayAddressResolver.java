package com.github.lystran.mochat.runtime.topology;

@FunctionalInterface
public interface GatewayAddressResolver {
    String resolve(String gatewayPod);
}
