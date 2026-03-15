package com.github.lystran.mochat.accessgateway.runtime;

import jakarta.inject.Singleton;

@Singleton
/**
 * 暴露网关存活和就绪状态，并把 drain 请求转给真正的退场管理器。
 */
public final class GatewayIngressLifecycle {
    private final GatewayDrainManager gatewayDrainManager;

    /**
     * 记录当前网关的退场状态来源。
     */
    public GatewayIngressLifecycle(GatewayDrainManager gatewayDrainManager) {
        this.gatewayDrainManager = gatewayDrainManager;
    }

    /**
     * 进程只要还活着就返回 true，便于存活探针判断。
     */
    public boolean isLive() {
        return true;
    }

    /**
     * drain 之后不再接收新流量，所以 readiness 需要立刻变成 false。
     */
    public boolean isReady() {
        return !gatewayDrainManager.isDraining();
    }

    /**
     * 触发网关退场，让它先拒绝新连接，再等待旧连接慢慢断开。
     */
    public void startDrain() {
        gatewayDrainManager.startDrain();
    }
}
