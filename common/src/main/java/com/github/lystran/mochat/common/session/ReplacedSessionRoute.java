package com.github.lystran.mochat.common.session;

/**
 * 表示这次新登录顶掉的那条旧在线记录。
 */
public record ReplacedSessionRoute(
    String gatewayPod,
    String connectionId,
    String sessionId,
    long sessionVersion,
    long routeEpoch
) {
}
