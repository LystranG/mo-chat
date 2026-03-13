package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.runtime.NativeRuntimeDefaults;
import io.micronaut.runtime.Micronaut;

public final class MessageServiceApplication {
    private MessageServiceApplication() {
    }

    public static void main(String[] args) {
        NativeRuntimeDefaults.apply();
        Micronaut.run(MessageServiceApplication.class, args);
    }
}
