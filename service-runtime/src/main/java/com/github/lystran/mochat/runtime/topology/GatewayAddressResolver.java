package com.github.lystran.mochat.runtime.topology;

/**
 * 把网关身份名换成真正可访问的地址。
 */
@FunctionalInterface
public interface GatewayAddressResolver {
    /**
     * 根据网关身份名算出目标地址。
     */
    String resolve(String gatewayPod);
}
