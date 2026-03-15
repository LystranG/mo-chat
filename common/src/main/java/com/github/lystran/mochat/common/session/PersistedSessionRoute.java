package com.github.lystran.mochat.common.session;

/**
 * 表示把在线路由写入存储后得到的结果。
 */
public record PersistedSessionRoute(long routeEpoch, ReplacedSessionRoute replacedRoute) {
    /**
     * 创建一条没有旧路由被顶掉的写入结果。
     */
    public PersistedSessionRoute(long routeEpoch) {
        this(routeEpoch, null);
    }

    /**
     * 返回一条“这次没有真正写入路由”的空结果。
     */
    public static PersistedSessionRoute none() {
        return new PersistedSessionRoute(0L, null);
    }
}
