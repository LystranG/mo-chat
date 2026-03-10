package com.github.lystran.mochat.apiservice;

import io.micronaut.runtime.Micronaut;

public final class ApiServiceApplication {
    private ApiServiceApplication() {
    }

    public static void main(String[] args) {
        Micronaut.run(ApiServiceApplication.class, args);
    }
}
