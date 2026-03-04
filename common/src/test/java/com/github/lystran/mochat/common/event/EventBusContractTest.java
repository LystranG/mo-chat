package com.github.lystran.mochat.common.event;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class EventBusContractTest {
    @Test
    void publishUsesTransportSafeStringPayload() {
        var publishMethod = Arrays.stream(EventBus.class.getMethods())
            .filter(method -> method.getName().equals("publish"))
            .findFirst()
            .orElseThrow();

        assertArrayEquals(new Class<?>[]{String.class, String.class}, publishMethod.getParameterTypes());
    }
}
