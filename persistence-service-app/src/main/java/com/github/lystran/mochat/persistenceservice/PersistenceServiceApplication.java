package com.github.lystran.mochat.persistenceservice;

import io.micronaut.runtime.Micronaut;

public final class PersistenceServiceApplication {
    private PersistenceServiceApplication() {
    }

    public static void main(String[] args) {
        Micronaut.run(PersistenceServiceApplication.class, args);
    }
}
