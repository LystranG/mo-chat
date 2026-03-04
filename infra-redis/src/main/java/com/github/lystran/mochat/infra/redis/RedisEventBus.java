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
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();

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
    public void publish(String topic, String event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        publisher.publish(topic, event);
    }

    @Override
    public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");

        synchronized (subscriptionLock) {
            var subscribers = subscribersByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
            boolean firstSubscriber = subscribers.isEmpty();
            subscribers.add(subscriber);

            if (firstSubscriber) {
                subscriberConnection.sync().subscribe(topic);
            }
        }

        return () -> {
            synchronized (subscriptionLock) {
                var currentSubscribers = subscribersByTopic.get(topic);
                if (currentSubscribers == null) {
                    return;
                }

                currentSubscribers.remove(subscriber);
                if (currentSubscribers.isEmpty()) {
                    subscribersByTopic.remove(topic, currentSubscribers);
                    subscriberConnection.sync().unsubscribe(topic);
                }
            }
        };
    }
}
