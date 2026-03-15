package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

/**
 * message-service 的启动入口。
 */
public final class MessageServiceApplication {
    /**
     * 禁止直接创建启动类实例。
     */
    private MessageServiceApplication() {
    }

    /**
     * 应用原生运行默认设置并启动 Micronaut。
     */
    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(MessageServiceApplication.class, args);
    }
}
