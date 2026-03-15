package com.github.lystran.mochat.accessgateway.runtime;

import java.util.Optional;

/**
 * 只面向当前网关本地连接的目录，供踢旧连接和查询本地状态使用。
 */
public interface LocalGatewayConnectionDirectory {
    /**
     * 按用户和连接信息尝试关掉一条本地连接。
     */
    boolean kickConnection(long userId, String connectionId, long sessionVersion, long expectedRouteEpoch, String reason);

    /**
     * 查询当前网关里某条连接的实时状态。
     */
    Optional<LocalConnectionStateSnapshot> findLocalConnectionState(long userId, String connectionId);

    /**
     * 关闭当前网关上所有已经完成绑定的连接。
     */
    int closeBoundConnections();
}
