package com.github.lystran.mochat.accessgateway.runtime;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/internal/lifecycle")
@Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
/**
 * 给容器探针和运维脚本使用的内部生命周期接口。
 */
final class AccessGatewayLifecycleController {
    private final GatewayDrainManager gatewayDrainManager;

    /**
     * 记录当前网关的退场状态。
     */
    AccessGatewayLifecycleController(GatewayDrainManager gatewayDrainManager) {
        this.gatewayDrainManager = gatewayDrainManager;
    }

    @Get("/livez")
    /**
     * 返回存活状态。
     */
    HttpResponse<String> livez() {
        return HttpResponse.ok("alive");
    }

    @Get("/readyz")
    /**
     * 返回就绪状态；开始 drain 后主动摘出新流量。
     */
    HttpResponse<String> readyz() {
        if (gatewayDrainManager.isDraining()) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("draining");
        }
        return HttpResponse.ok("ready");
    }

    @Get("/drain")
    /**
     * 手动触发网关退场流程。
     */
    HttpResponse<String> drain() {
        gatewayDrainManager.startDrain();
        return HttpResponse.ok("draining");
    }
}
