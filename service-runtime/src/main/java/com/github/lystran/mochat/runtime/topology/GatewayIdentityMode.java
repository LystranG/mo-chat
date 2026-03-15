package com.github.lystran.mochat.runtime.topology;

/**
 * 定义当前网关自己的身份名从哪里来。
 */
public enum GatewayIdentityMode {
    CONFIGURED,
    POD_METADATA
}
