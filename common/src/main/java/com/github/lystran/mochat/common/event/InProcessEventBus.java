package com.github.lystran.mochat.common.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 只在当前进程里转发消息的事件总线实现。
 */
public final class InProcessEventBus implements EventBus {
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();

    /**
     * 把消息发给当前进程里订了这个事件名的所有回调。
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
                // 某一个回调出错时，其他回调照样继续收消息，免得一处出错把整条链路卡住。
            }
        }
    }

    /**
     * 给某个事件名挂上一个本地回调，并返回取消监听的方法。
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
