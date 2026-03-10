package com.github.lystran.mochat.messageservice;

import io.micronaut.runtime.Micronaut;

public final class MessageServiceApplication {
    private MessageServiceApplication() {
    }

    public static void main(String[] args) {
        Micronaut.run(MessageServiceApplication.class, args);
    }
}
