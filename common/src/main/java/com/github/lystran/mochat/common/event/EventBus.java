package com.github.lystran.mochat.common.event;

import java.util.function.Consumer;

/**
 * 用来在模块之间发消息、收消息。
 */
public interface EventBus {
    /**
     * 往指定分类里发一条消息。
     */
    void publish(String topic, String event);

    /**
     * 订阅某个分类下的消息，并返回一个以后可以取消订阅的句柄。
     */
    AutoCloseable subscribe(String topic, Consumer<String> subscriber);
}
