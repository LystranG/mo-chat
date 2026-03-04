package com.github.lystran.mochat.connection;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitHandlerTest {
    @Test
    void closesChannelWhenTokenBucketIsExhausted() {
        var timer = new ManualTimer();
        var channel = new EmbeddedChannel(new RateLimitHandler(2, timer, 250));

        assertTrue(channel.writeInbound("m1"));
        assertEquals("m1", channel.readInbound());
        assertTrue(channel.writeInbound("m2"));
        assertEquals("m2", channel.readInbound());

        channel.writeInbound("m3");
        assertFalse(channel.isOpen());
    }

    @Test
    void timerRefillAddsOnlyAvailableTokens() {
        var timer = new ManualTimer();
        var channel = new EmbeddedChannel(new RateLimitHandler(4, timer, 250));

        assertTrue(channel.writeInbound("m1"));
        assertEquals("m1", channel.readInbound());
        assertTrue(channel.writeInbound("m2"));
        assertEquals("m2", channel.readInbound());
        assertTrue(channel.writeInbound("m3"));
        assertEquals("m3", channel.readInbound());
        assertTrue(channel.writeInbound("m4"));
        assertEquals("m4", channel.readInbound());

        timer.advanceBy(250, TimeUnit.MILLISECONDS);

        assertTrue(channel.writeInbound("m5"));
        assertEquals("m5", channel.readInbound());

        channel.writeInbound("m6");
        assertFalse(channel.isOpen());
    }

    private static final class ManualTimer implements Timer {
        private final PriorityQueue<ManualTimeout> queue =
            new PriorityQueue<>(Comparator.comparingLong(ManualTimeout::deadlineNanos));

        private long nowNanos;
        private boolean stopped;

        @Override
        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            if (stopped) {
                throw new IllegalStateException("timer stopped");
            }
            var timeout = new ManualTimeout(this, task, nowNanos + unit.toNanos(delay));
            queue.add(timeout);
            return timeout;
        }

        @Override
        public Set<Timeout> stop() {
            stopped = true;
            var remaining = new HashSet<Timeout>(queue);
            queue.clear();
            return remaining;
        }

        void advanceBy(long delay, TimeUnit unit) {
            nowNanos += unit.toNanos(delay);
            while (!queue.isEmpty() && queue.peek().deadlineNanos() <= nowNanos) {
                var timeout = queue.poll();
                if (timeout.cancelled) {
                    continue;
                }

                timeout.expired = true;
                try {
                    timeout.task.run(timeout);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        }
    }

    private static final class ManualTimeout implements Timeout {
        private final ManualTimer timer;
        private final TimerTask task;
        private final long deadlineNanos;

        private boolean cancelled;
        private boolean expired;

        private ManualTimeout(ManualTimer timer, TimerTask task, long deadlineNanos) {
            this.timer = timer;
            this.task = task;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean isExpired() {
            return expired;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean cancel() {
            if (cancelled || expired) {
                return false;
            }
            cancelled = true;
            return true;
        }

        private long deadlineNanos() {
            return deadlineNanos;
        }
    }
}
