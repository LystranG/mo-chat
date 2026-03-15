package com.github.lystran.mochat.apiservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

/**
 * `api-service` 的启动入口。
 */
public final class ApiServiceApplication {
    /**
     * 禁止外部创建启动类实例。
     */
    private ApiServiceApplication() {
    }

    /**
     * 先补齐运行时默认值，再启动 `api-service`。
     */
    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(ApiServiceApplication.class, args);
    }
}
