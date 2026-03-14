package com.github.lystran.mochat.accessgateway.runtime;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/internal/lifecycle")
@Requires(property = "mochat.access-gateway.tcp.enabled", notEquals = "false", defaultValue = "true")
final class AccessGatewayLifecycleController {
    private final GatewayDrainManager gatewayDrainManager;

    AccessGatewayLifecycleController(GatewayDrainManager gatewayDrainManager) {
        this.gatewayDrainManager = gatewayDrainManager;
    }

    @Get("/livez")
    HttpResponse<String> livez() {
        return HttpResponse.ok("alive");
    }

    @Get("/readyz")
    HttpResponse<String> readyz() {
        if (gatewayDrainManager.isDraining()) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("draining");
        }
        return HttpResponse.ok("ready");
    }

    @Get("/drain")
    HttpResponse<String> drain() {
        gatewayDrainManager.startDrain();
        return HttpResponse.ok("draining");
    }
}
