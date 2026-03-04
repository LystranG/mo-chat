package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboundEventSubscriberTest {
    @Test
    void offlineRecipientIsQueuedWithCap50() {
        var eventBus = new InProcessEventBus();
        var directory = new InMemoryDirectory();
        var queue = new RecordingOfflineQueue();
        var subscriber = new OutboundEventSubscriber(eventBus, directory, queue);
        subscriber.start();

        eventBus.publish(OutboundEventSubscriber.DEFAULT_OUTBOUND_TOPIC, "42|PRIVATE_MESSAGE|PROTOBUF|payload-base64");

        assertEquals(1, queue.entries.size());
        var enqueued = queue.entries.getFirst();
        assertEquals(42L, enqueued.userId());
        assertEquals("PRIVATE_MESSAGE|PROTOBUF|payload-base64", enqueued.payload());
        assertEquals(50, enqueued.maxQueueSize());
    }

    @Test
    void failedWriteIsQueuedWithCap50() {
        var eventBus = new InProcessEventBus();
        var directory = new InMemoryDirectory();
        var queue = new RecordingOfflineQueue();
        var subscriber = new OutboundEventSubscriber(eventBus, directory, queue);
        subscriber.start();

        var failingChannel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(new IllegalStateException("write failed"));
            }
        });
        directory.bind(42L, failingChannel);

        eventBus.publish(OutboundEventSubscriber.DEFAULT_OUTBOUND_TOPIC, "42|PRIVATE_MESSAGE|PROTOBUF|payload-base64");

        assertEquals(1, queue.entries.size());
        var enqueued = queue.entries.getFirst();
        assertEquals(42L, enqueued.userId());
        assertEquals("PRIVATE_MESSAGE|PROTOBUF|payload-base64", enqueued.payload());
        assertEquals(50, enqueued.maxQueueSize());
    }

    @Test
    void offlineDeliveredAckIsNotQueued() {
        var eventBus = new InProcessEventBus();
        var directory = new InMemoryDirectory();
        var queue = new RecordingOfflineQueue();
        var subscriber = new OutboundEventSubscriber(eventBus, directory, queue);
        subscriber.start();

        eventBus.publish(OutboundEventSubscriber.DEFAULT_OUTBOUND_TOPIC, "42|DELIVERED_ACK|PROTOBUF|payload-base64");

        assertEquals(0, queue.entries.size());
    }

    @Test
    void failedWriteDeliveredAckIsNotQueued() {
        var eventBus = new InProcessEventBus();
        var directory = new InMemoryDirectory();
        var queue = new RecordingOfflineQueue();
        var subscriber = new OutboundEventSubscriber(eventBus, directory, queue);
        subscriber.start();

        var failingChannel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(new IllegalStateException("write failed"));
            }
        });
        directory.bind(42L, failingChannel);

        eventBus.publish(OutboundEventSubscriber.DEFAULT_OUTBOUND_TOPIC, "42|DELIVERED_ACK|PROTOBUF|payload-base64");

        assertEquals(0, queue.entries.size());
    }

    private static final class InMemoryDirectory implements UserChannelDirectory<Channel> {
        private final ConcurrentMap<Long, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public void bind(long userId, Channel channelRef) {
            channels.put(userId, channelRef);
        }

        @Override
        public Optional<Channel> find(long userId) {
            return Optional.ofNullable(channels.get(userId));
        }

        @Override
        public boolean unbind(long userId, Channel channelRef) {
            return channels.remove(userId, channelRef);
        }
    }

    private static final class RecordingOfflineQueue implements OfflineQueue {
        private final List<EnqueuedEntry> entries = new ArrayList<>();

        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
            entries.add(new EnqueuedEntry(userId, payload, maxQueueSize));
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            return List.of();
        }
    }

    private record EnqueuedEntry(long userId, String payload, int maxQueueSize) {
    }
}
