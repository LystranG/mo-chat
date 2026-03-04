package com.github.lystran.mochat.common.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InProcessEventBusTest {
    @Test
    void continuesPublishingWhenSubscriberThrows() {
        var eventBus = new InProcessEventBus();
        var successfulInvocations = new AtomicInteger();

        eventBus.subscribe("topic", ignored -> {
            throw new IllegalStateException("boom");
        });
        eventBus.subscribe("topic", ignored -> successfulInvocations.incrementAndGet());

        assertDoesNotThrow(() -> eventBus.publish("topic", "event"));
        assertEquals(1, successfulInvocations.get());
    }
}
