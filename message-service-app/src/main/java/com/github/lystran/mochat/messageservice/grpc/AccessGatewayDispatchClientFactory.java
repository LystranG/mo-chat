package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;

/**
 * 负责按目标网关地址创建 gRPC 客户端。
 */
public interface AccessGatewayDispatchClientFactory {
    /**
     * 创建一个能直接调用指定 access-gateway 的阻塞式客户端。
     */
    AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub createBlockingStub(String targetAddress);
}
