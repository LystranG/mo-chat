package com.github.lystran.mochat.persistenceservice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistenceServiceHistoryOwnershipTest {
    @Test
    void dedicatedPersistenceRuntimeDoesNotCarryHistoryQueryTypes() {
        ClassLoader classLoader = PersistenceServiceHistoryOwnershipTest.class.getClassLoader();
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "com.github.lystran.mochat.logic.http.HistoryController",
            false,
            classLoader
        ));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
            "com.github.lystran.mochat.logic.http.ConversationController",
            false,
            classLoader
        ));
    }
}
