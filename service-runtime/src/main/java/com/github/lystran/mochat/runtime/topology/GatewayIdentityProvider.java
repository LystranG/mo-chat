package com.github.lystran.mochat.runtime.topology;

/**
 * 提供当前网关实例自己的身份名。
 */
@FunctionalInterface
public interface GatewayIdentityProvider {
    /**
     * 返回当前网关实例在路由里使用的身份名。
     */
    String currentGatewayPod();
}
