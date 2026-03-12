package com.github.lystran.mochat.accessgateway;

import io.micronaut.runtime.Micronaut;

public final class AccessGatewayApplication {
    private AccessGatewayApplication() {
    }

    public static void main(String[] args) {
        Micronaut.run(AccessGatewayApplication.class, args);
    }
}
