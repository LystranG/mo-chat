package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

/**
 * 接入网关启动入口，负责先应用原生运行时默认设置，再启动 Micronaut。
 */
public final class AccessGatewayApplication {
    /**
     * 工具类不允许外部创建实例。
     */
    private AccessGatewayApplication() {
    }

    /**
     * 启动 access-gateway 进程。
     */
    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(AccessGatewayApplication.class, args);
    }
}
