package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageCommand;
import io.grpc.Channel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceGrpcConnectivityTest {
    @Test
    void acceptsPrivateSendOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0
        ))) {
            var stub = context.getBean(MessageCommandApiGrpc.MessageCommandApiBlockingStub.class);
            var response = stub.sendPrivateMessage(SendPrivateMessageCommand.newBuilder()
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setSenderUid(21L)
                .setClientMsgId(1001L)
                .setConversationId(55L)
                .setRecipientUid(34L)
                .build());

            assertTrue(response.getAccepted());
            assertEquals(1001L, response.getClientMsgId());
            assertTrue(response.getMsgId() > 0);
            assertTrue(response.getSeq() > 0);
        }
    }

    @Factory
    static final class TestGrpcClientFactory {
        @Singleton
        MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return MessageCommandApiGrpc.newBlockingStub(channel);
        }
    }
}
