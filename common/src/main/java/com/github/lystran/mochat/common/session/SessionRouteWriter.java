package com.github.lystran.mochat.common.session;

/**
 * 负责把“某个用户现在连在哪个网关上”写进共享存储。
 */
public interface SessionRouteWriter<C> {
    /**
     * 写入最新在线路由，并返回这次写入拿到的版本信息。
     */
    PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, C channelRef);

    /**
     * 心跳续租时刷新这条在线路由；默认直接视为成功。
     */
    default boolean renewRoute(ResolvedSession resolvedSession, C channelRef, PersistedSessionRoute persistedRoute) {
        return true;
    }

    /**
     * 连接关闭时清掉在线路由；默认不做任何事。
     */
    default boolean clearRoute(ResolvedSession resolvedSession, C channelRef, PersistedSessionRoute persistedRoute) {
        return false;
    }

    /**
     * 返回一个不碰共享存储的空实现。
     */
    static <C> SessionRouteWriter<C> noop() {
        return (resolvedSession, channelRef) -> PersistedSessionRoute.none();
    }
}
