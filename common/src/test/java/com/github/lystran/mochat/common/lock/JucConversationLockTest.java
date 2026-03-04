package com.github.lystran.mochat.common.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JucConversationLockTest {
    @Test
    void serializesSameConversation() throws Exception {
        var lock = new JucConversationLock();
        var pool = Executors.newFixedThreadPool(2);
        var insideCount = new AtomicInteger();
        var maxInsideCount = new AtomicInteger();
        var firstEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

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
            try (var ignored = lock.acquire(42L)) {
                int entered = insideCount.incrementAndGet();
                maxInsideCount.accumulateAndGet(entered, Math::max);
                insideCount.decrementAndGet();
            }
            return null;
        });

        Thread.sleep(100);
        assertEquals(1, insideCount.get());

        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, maxInsideCount.get());
    }
}
