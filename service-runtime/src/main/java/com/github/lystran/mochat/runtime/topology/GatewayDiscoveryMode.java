package com.github.lystran.mochat.runtime.topology;

/**
 * 定义系统该用哪种办法找到目标网关地址。
 */
public enum GatewayDiscoveryMode {
    AUTO,
    STATIC_MAP,
    KUBERNETES_DNS
}
