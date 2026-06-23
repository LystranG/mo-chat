package com.github.multimediaservice.runtime;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * 按配置组装 multimedia-service 运行时需要的基础组件。
 */
@Factory
public final class MultimediaServiceRuntimeFactory {
    /**
     * 提供进程内事件总线实现，用于模块间消息传递。
     */
    @Singleton
    EventBus eventBus() {
        return new InProcessEventBus();
    }
}