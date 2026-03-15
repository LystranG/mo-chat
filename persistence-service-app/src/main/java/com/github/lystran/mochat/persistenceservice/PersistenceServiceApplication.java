package com.github.lystran.mochat.persistenceservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

/**
 * 持久化服务的启动入口。
 */
public final class PersistenceServiceApplication {
    /**
     * 禁止外部创建启动类实例。
     */
    private PersistenceServiceApplication() {
    }

    /**
     * 先补上原生运行时默认配置，再启动持久化服务。
     */
    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(PersistenceServiceApplication.class, args);
    }
}
