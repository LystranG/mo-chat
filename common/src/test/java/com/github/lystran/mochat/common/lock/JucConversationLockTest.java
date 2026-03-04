package com.github.lystran.mochat.common.lock;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JucConversationLockTest {
    @Test
    void serializesSameConversation() throws Exception {
        var lock = new JucConversationLock();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var insideCount = new AtomicInteger();
        var maxInsideCount = new AtomicInteger();
        var firstEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try {
            var first = pool.submit(() -> {
                try (var ignored = lock.acquire(42L)) {
                    int entered = insideCount.incrementAndGet();
                    maxInsideCount.accumulateAndGet(entered, Math::max);
                    firstEntered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    insideCount.decrementAndGet();
                }
                return null;
            });

            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            var second = pool.submit(() -> {
                secondStarted.countDown();
                try (var ignored = lock.acquire(42L)) {
                    secondEntered.countDown();
                    int entered = insideCount.incrementAndGet();
                    maxInsideCount.accumulateAndGet(entered, Math::max);
                    insideCount.decrementAndGet();
                }
                return null;
            });

            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
            assertEquals(1, insideCount.get());

            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            assertEquals(1, maxInsideCount.get());
        } finally {
            shutdownAndAwait(pool);
        }
    }

    @Test
    void cleansUpConversationLockEntriesAfterRelease() throws Exception {
        var lock = new JucConversationLock();

        for (long conversationId = 0; conversationId < 1_000; conversationId++) {
            try (var ignored = lock.acquire(conversationId)) {
                // no-op
            }
        }

        assertTrue(locksMap(lock).isEmpty());
    }

    private static ConcurrentMap<?, ?> locksMap(JucConversationLock lock) throws Exception {
        Field field = JucConversationLock.class.getDeclaredField("locksByConversationId");
        field.setAccessible(true);
        return (ConcurrentMap<?, ?>) field.get(lock);
    }

    private static void shutdownAndAwait(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (pool.awaitTermination(2, TimeUnit.SECONDS)) {
            return;
        }

        pool.shutdownNow();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }
}
