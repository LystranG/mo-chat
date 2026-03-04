package com.github.lystran.mochat.common.event;

import java.util.function.Consumer;

public interface EventBus {
    void publish(String topic, String event);

    AutoCloseable subscribe(String topic, Consumer<String> subscriber);
}
