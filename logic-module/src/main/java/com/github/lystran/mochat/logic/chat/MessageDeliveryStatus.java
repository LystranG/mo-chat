package com.github.lystran.mochat.logic.chat;

/**
 * 表示一次在线投递尝试的结果。
 */
public enum MessageDeliveryStatus {
    /** 已经成功写到接收方当前在线的连接里。 */
    DELIVERED,
    /** 当前没找到这个人的在线连接。 */
    USER_OFFLINE,
    /** Redis 里的在线路由已经过期，需要重新查一次。 */
    ROUTE_STALE,
    /** 已经找到目标网关，但真正写连接时失败了。 */
    WRITE_FAILED
}
