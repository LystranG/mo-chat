package com.github.lystran.mochat.persistenceservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

public final class PersistenceServiceApplication {
    private PersistenceServiceApplication() {
    }

    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(PersistenceServiceApplication.class, args);
    }
}
