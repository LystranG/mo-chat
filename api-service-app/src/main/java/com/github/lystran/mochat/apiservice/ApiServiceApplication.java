package com.github.lystran.mochat.apiservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

public final class ApiServiceApplication {
    private ApiServiceApplication() {
    }

    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(ApiServiceApplication.class, args);
    }
}
