package com.github.lystran.mochat.runtime.topology;

/**
 * 直接使用配置文件里写死的网关身份名。
 */
public final class StaticGatewayIdentityProvider implements GatewayIdentityProvider {
    private final String gatewayPod;

    /**
     * 用固定身份名创建提供器。
     */
    public StaticGatewayIdentityProvider(String gatewayPod) {
        this.gatewayPod = normalizeRequiredValue(gatewayPod, "gatewayPod");
    }

    /**
     * 返回配置里写死的身份名。
     */
    @Override
    public String currentGatewayPod() {
        return gatewayPod;
    }

    /**
     * 去掉两头空白，并拦住空值。
     */
    static String normalizeRequiredValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.trim();
    }
}
