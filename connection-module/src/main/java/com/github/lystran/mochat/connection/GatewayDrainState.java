package com.github.lystran.mochat.connection;

@FunctionalInterface
/**
 * 暴露网关是否已经进入退场阶段，供绑定流程判断还能不能接新的用户连接。
 */
public interface GatewayDrainState {
    GatewayDrainState ACCEPTING = () -> false;

    /**
     * 返回当前网关是不是已经开始拒绝新的连接绑定，只等待旧连接退场。
     */
    boolean isDraining();

    /**
     * 返回一个始终允许新连接接入的默认实现。
     */
    static GatewayDrainState accepting() {
        return ACCEPTING;
    }
}
