package com.github.lystran.mochat;

import io.micronaut.runtime.Micronaut;

/**
 * 整个应用的启动入口。
 */
public class Application {
    /**
     * 把程序交给 Micronaut 启动，后续由框架接管各个组件的创建和关闭。
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}