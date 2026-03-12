package com.github.lystran.mochat.messageservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceHistoryOwnershipTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dedicatedMessageRuntimeDoesNotMaterializeHistoryQueryBeans() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Class messageCommandServiceType = Class.forName(
                "com.github.lystran.mochat.messageservice.grpc.MessageCommandGrpcService"
            );
            Class historyControllerType = Class.forName("com.github.lystran.mochat.logic.http.HistoryController");
            Class conversationControllerType = Class.forName("com.github.lystran.mochat.logic.http.ConversationController");

            assertTrue(context.containsBean(messageCommandServiceType));
            assertFalse(context.containsBean(historyControllerType));
            assertFalse(context.containsBean(conversationControllerType));
        }
    }
}
