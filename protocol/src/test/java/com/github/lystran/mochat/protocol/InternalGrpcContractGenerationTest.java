package com.github.lystran.mochat.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InternalGrpcContractGenerationTest {
    @Test
    void generatesInternalGrpcContractsForCoreServices() {
        assertAll(
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc")),
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest")),
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc")),
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest")),
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc")),
            () -> assertNotNull(Class.forName("com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageCommand"))
        );
    }
}
