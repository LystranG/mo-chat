package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;

public interface AccessGatewayDispatchClientFactory {
    AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub createBlockingStub(String targetAddress);
}
