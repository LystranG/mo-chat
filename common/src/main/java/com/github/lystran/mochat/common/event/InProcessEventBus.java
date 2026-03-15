package com.github.lystran.mochat.common.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用当前进程里的内存结构实现一个轻量消息总线。
 */
public final class InProcessEventBus implements EventBus {
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();

    /**
     * 把消息发给当前进程里订阅了这个分类的所有处理方。
     */
    @Override
    public void publish(String topic, String event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");

        var subscribers = subscribersByTopic.get(topic);
        if (subscribers == null) {
            return;
        }

        for (var subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
                // Best-effort fan-out: one failing subscriber must not block others.
            }
        }
    }

    /**
     * 记下某个分类的处理方，并返回一个以后可以取消订阅的句柄。
     */
    @Override
    public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");

        var subscribers = subscribersByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
        subscribers.add(subscriber);

        return () -> {
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) {
                subscribersByTopic.remove(topic, subscribers);
            }
        };
    }
}
