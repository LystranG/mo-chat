package com.github.lystran.mochat.common.event;

import java.util.function.Consumer;

public interface EventBus {
    void publish(String topic, Object event);

    AutoCloseable subscribe(String topic, Consumer<Object> subscriber);
}
