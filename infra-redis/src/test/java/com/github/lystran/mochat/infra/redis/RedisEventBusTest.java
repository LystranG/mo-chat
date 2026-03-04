package com.github.lystran.mochat.infra.redis;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisEventBusTest {
    @Test
    void subscribeFailureRollsBackLocalStateForRetry() throws Exception {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> publisher = mock(RedisCommands.class);
        @SuppressWarnings("unchecked")
        StatefulRedisPubSubConnection<String, String> subscriberConnection = mock(StatefulRedisPubSubConnection.class);
        @SuppressWarnings("unchecked")
        RedisPubSubCommands<String, String> pubSubCommands = mock(RedisPubSubCommands.class);
        when(subscriberConnection.sync()).thenReturn(pubSubCommands);

        doThrow(new RuntimeException("subscribe failed"))
            .doNothing()
            .when(pubSubCommands)
            .subscribe("topic");

        var eventBus = new RedisEventBus(publisher, subscriberConnection);

        assertThrows(RuntimeException.class, () -> eventBus.subscribe("topic", ignored -> {
        }));

        AutoCloseable retrySubscriber = eventBus.subscribe("topic", ignored -> {
        });
        retrySubscriber.close();

        verify(pubSubCommands, times(2)).subscribe("topic");
        verify(pubSubCommands).unsubscribe("topic");
    }

    @Test
    void closeAndSubscribeRaceEndsWithActiveSubscription() throws Exception {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> publisher = mock(RedisCommands.class);
        @SuppressWarnings("unchecked")
        StatefulRedisPubSubConnection<String, String> subscriberConnection = mock(StatefulRedisPubSubConnection.class);
        @SuppressWarnings("unchecked")
        RedisPubSubCommands<String, String> pubSubCommands = mock(RedisPubSubCommands.class);
        when(subscriberConnection.sync()).thenReturn(pubSubCommands);

        var commandOrder = new CopyOnWriteArrayList<String>();
        var unsubscribeEntered = new CountDownLatch(1);
        var releaseUnsubscribe = new CountDownLatch(1);

        doAnswer(invocation -> {
            commandOrder.add("SUB");
            return null;
        }).when(pubSubCommands).subscribe("topic");
        doAnswer(invocation -> {
            unsubscribeEntered.countDown();
            if (!releaseUnsubscribe.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("unsubscribe was not released in time");
            }
            commandOrder.add("UNSUB");
            return null;
        }).when(pubSubCommands).unsubscribe("topic");

        var eventBus = new RedisEventBus(publisher, subscriberConnection);
        AutoCloseable firstSubscriber = eventBus.subscribe("topic", ignored -> {
        });

        var closeError = new AtomicReference<Throwable>();
        Thread closeThread = new Thread(() -> {
            try {
                firstSubscriber.close();
            } catch (Throwable throwable) {
                closeError.set(throwable);
            }
        });
        closeThread.start();
        assertTrue(unsubscribeEntered.await(5, TimeUnit.SECONDS));

        var secondSubscriber = new AtomicReference<AutoCloseable>();
        var subscribeError = new AtomicReference<Throwable>();
        Thread subscribeThread = new Thread(() -> {
            try {
                secondSubscriber.set(eventBus.subscribe("topic", ignored -> {
                }));
            } catch (Throwable throwable) {
                subscribeError.set(throwable);
            }
        });
        subscribeThread.start();

        releaseUnsubscribe.countDown();
        closeThread.join(5_000);
        subscribeThread.join(5_000);

        assertNull(closeError.get());
        assertNull(subscribeError.get());
        assertEquals("SUB", commandOrder.get(commandOrder.size() - 1));
        assertEquals(List.of("SUB", "UNSUB", "SUB"), commandOrder);

        secondSubscriber.get().close();
    }
}
