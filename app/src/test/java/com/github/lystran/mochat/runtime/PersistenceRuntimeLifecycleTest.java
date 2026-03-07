package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.persistence.MqConsumer;
import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PersistenceRuntimeLifecycleTest {
    @Test
    void startDoesNotStartConsumerWhenDisabled() {
        RecordingPushConsumer delegate = new RecordingPushConsumer();
        PersistenceRuntimeLifecycle lifecycle = new PersistenceRuntimeLifecycle(
            new RocketMqPersistenceConsumer(delegate, mock(MqConsumer.class)),
            false
        );

        lifecycle.start();
        lifecycle.close();

        assertEquals(0, delegate.startCount);
        assertEquals(0, delegate.shutdownCount);
    }

    @Test
    void closeShutsDownConsumerAfterStart() {
        RecordingPushConsumer delegate = new RecordingPushConsumer();
        PersistenceRuntimeLifecycle lifecycle = new PersistenceRuntimeLifecycle(
            new RocketMqPersistenceConsumer(delegate, mock(MqConsumer.class)),
            true
        );

        lifecycle.start();
        lifecycle.close();

        assertEquals(1, delegate.startCount);
        assertEquals(1, delegate.shutdownCount);
    }

    private static final class RecordingPushConsumer extends DefaultMQPushConsumer {
        private int startCount;
        private int shutdownCount;

        RecordingPushConsumer() {
            super("persistence-runtime-lifecycle-test");
        }

        @Override
        public void start() throws MQClientException {
            startCount++;
        }

        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }
}
