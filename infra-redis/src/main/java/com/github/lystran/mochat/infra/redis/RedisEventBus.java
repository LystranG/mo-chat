package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.event.EventBus;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RedisEventBus implements EventBus {
    private final RedisCommands<String, String> publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriberConnection;
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<Object>>> subscribersByTopic = new ConcurrentHashMap<>();

    public RedisEventBus(
        RedisCommands<String, String> publisher,
        StatefulRedisPubSubConnection<String, String> subscriberConnection
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.subscriberConnection = Objects.requireNonNull(subscriberConnection, "subscriberConnection");
        this.subscriberConnection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                var subscribers = subscribersByTopic.get(channel);
                if (subscribers == null) {
                    return;
                }

                for (var subscriber : subscribers) {
                    try {
                        subscriber.accept(message);
                    } catch (RuntimeException ignored) {
                        // Keep fan-out best-effort, matching in-process EventBus behavior.
                    }
                }
            }
        });
    }

    @Override
    public void publish(String topic, Object event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        if (!(event instanceof String payload)) {
            throw new IllegalArgumentException("RedisEventBus only supports String payloads");
        }

        publisher.publish(topic, payload);
    }

    @Override
    public AutoCloseable subscribe(String topic, Consumer<Object> subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");

        var subscribers = subscribersByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
        subscribers.add(subscriber);

        if (subscribers.size() == 1) {
            subscriberConnection.sync().subscribe(topic);
        }

        return () -> {
            var currentSubscribers = subscribersByTopic.get(topic);
            if (currentSubscribers == null) {
                return;
            }

            currentSubscribers.remove(subscriber);
            if (currentSubscribers.isEmpty()) {
                subscribersByTopic.remove(topic, currentSubscribers);
                subscriberConnection.sync().unsubscribe(topic);
            }
        };
    }
}
