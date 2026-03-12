package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;

public interface AccessGatewayDispatchClient {
    KickConnectionResponse kickConnection(KickConnectionRequest request);
}
