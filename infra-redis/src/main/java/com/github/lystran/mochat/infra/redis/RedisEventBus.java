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

/**
 * 用 Redis 的发布/订阅功能在不同模块之间转发字符串事件。
 */
public final class RedisEventBus implements EventBus {
    private final RedisCommands<String, String> publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriberConnection;
    private final ConcurrentMap<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();
    // 改本地回调列表和向 Redis 订/退订必须一起做完，免得重复订阅或太早退订。
    private final Object subscriptionLock = new Object();

    /**
     * 创建事件总线，并把 Redis 推过来的消息逐个转给本地回调。
     */
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
                        // 尽量把消息继续交给其他回调；某一个回调出错，不影响其余回调继续收消息。
                    }
                }
            }
        });
    }

    /**
     * 把事件发到指定事件名下。
     */
    @Override
    public void publish(String topic, String event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        publisher.publish(topic, event);
    }

    /**
     * 登记一个本地回调；如果这是这个事件名下的第一个回调，就顺手告诉 Redis 开始转发。
     */
    @Override
    public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");

        synchronized (subscriptionLock) {
            var subscribers = subscribersByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>());
            // 只有本地回调从 0 个变成 1 个时，才真的去让 Redis 开始转发这类消息。
            boolean firstSubscriber = subscribers.isEmpty();
            subscribers.add(subscriber);

            if (firstSubscriber) {
                try {
                    subscriberConnection.sync().subscribe(topic);
                } catch (RuntimeException subscribeFailure) {
                    subscribers.remove(subscriber);
                    if (subscribers.isEmpty()) {
                        subscribersByTopic.remove(topic, subscribers);
                    }
                    throw subscribeFailure;
                }
            }
        }

        return () -> {
            synchronized (subscriptionLock) {
                var currentSubscribers = subscribersByTopic.get(topic);
                if (currentSubscribers == null) {
                    return;
                }

                currentSubscribers.remove(subscriber);
                // 只有最后一个本地回调移除时，才真的告诉 Redis 停止转发，免得误伤还在收消息的人。
                if (currentSubscribers.isEmpty()) {
                    subscribersByTopic.remove(topic, currentSubscribers);
                    subscriberConnection.sync().unsubscribe(topic);
                }
            }
        };
    }
}
