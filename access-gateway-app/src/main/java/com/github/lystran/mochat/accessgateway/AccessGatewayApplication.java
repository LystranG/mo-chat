package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

public final class AccessGatewayApplication {
    private AccessGatewayApplication() {
    }

    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(AccessGatewayApplication.class, args);
    }
}
