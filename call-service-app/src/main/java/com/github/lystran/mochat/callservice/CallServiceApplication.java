package com.github.lystran.mochat.callservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

/**
 * `call-service` 的启动入口，负责音视频通话信令、房间管理和离线通知。
 */
public final class CallServiceApplication {
    /**
     * 禁止外部创建启动类实例。
     */
    private CallServiceApplication() {
    }

    /**
     * 先补齐运行时默认值，再启动 `call-service`。
     */
    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(CallServiceApplication.class, args);
    }
}
