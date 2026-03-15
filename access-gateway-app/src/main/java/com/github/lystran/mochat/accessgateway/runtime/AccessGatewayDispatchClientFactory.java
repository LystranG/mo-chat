package com.github.lystran.mochat.accessgateway.runtime;

/**
 * 按目标地址创建网关内部调度客户端。
 */
public interface AccessGatewayDispatchClientFactory {
    /**
     * 为指定的网关地址创建一个可复用的内部调用客户端。
     */
    AccessGatewayDispatchClient createClient(String targetAddress);
}
