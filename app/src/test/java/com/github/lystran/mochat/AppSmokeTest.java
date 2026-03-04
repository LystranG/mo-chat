package com.github.lystran.mochat;

import io.micronaut.runtime.Micronaut;
import org.junit.jupiter.api.Test;

class AppSmokeTest {
    @Test
    void appStarts() {
        Micronaut.run(Application.class).close();
    }
}
