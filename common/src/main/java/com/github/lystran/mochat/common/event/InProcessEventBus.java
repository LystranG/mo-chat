package com.github.lystran.mochat.common.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class InProcessEventBus implements EventBus {
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();

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
