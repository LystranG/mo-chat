package com.github.lystran.mochat.accessgateway.runtime;

/**
 * 当前网关里某条本地连接的状态快照，用来判断这条连接现在是否还算数。
 *
 * @param sessionId 这条连接当前绑定的会话 ID
 * @param sessionVersion 当前会话版本，用来识别是不是旧登录状态
 * @param routeEpoch 当前在线路由的版本号，用来识别是不是旧连接
 * @param activeRouteOwner 这个网关现在是不是还负责这条长连接
 */
public record LocalConnectionStateSnapshot(
    String sessionId,
    long sessionVersion,
    long routeEpoch,
    boolean activeRouteOwner
) {
}
