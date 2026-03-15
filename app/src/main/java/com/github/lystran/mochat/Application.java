package com.github.lystran.mochat;

import io.micronaut.runtime.Micronaut;

/**
 * 兼容壳应用的启动入口。
 */
public class Application {
    /**
     * 启动这个兼容壳应用。
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
