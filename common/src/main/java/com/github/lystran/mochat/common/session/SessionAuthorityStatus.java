package com.github.lystran.mochat.common.session;

/**
 * 描述 session 当前为什么能用或不能用。
 */
public enum SessionAuthorityStatus {
    ACTIVE,
    INVALID,
    EXPIRED,
    REPLACED
}
