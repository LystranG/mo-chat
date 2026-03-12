package com.github.lystran.mochat.accessgateway.runtime;

import java.util.Optional;

public interface LocalGatewayConnectionDirectory {
    boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason);

    Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId);

    int closeBoundConnections();
}
