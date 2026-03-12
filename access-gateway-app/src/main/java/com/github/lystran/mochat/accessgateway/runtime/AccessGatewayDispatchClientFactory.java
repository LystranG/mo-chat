package com.github.lystran.mochat.accessgateway.runtime;

public interface AccessGatewayDispatchClientFactory {
    AccessGatewayDispatchClient createClient(String targetAddress);
}
