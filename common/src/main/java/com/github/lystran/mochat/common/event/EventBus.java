package com.github.lystran.mochat.common.event;

import java.util.function.Consumer;

/**
 * 在模块之间转发字符串消息的简单事件总线。
 */
public interface EventBus {
    /**
     * 往某个事件名下发一条消息。
     */
    void publish(String topic, String event);

    /**
     * 监听某个事件名下的新消息。
     */
    AutoCloseable subscribe(String topic, Consumer<String> subscriber);
}
