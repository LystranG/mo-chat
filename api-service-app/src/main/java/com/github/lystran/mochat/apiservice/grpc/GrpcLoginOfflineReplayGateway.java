package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import com.github.lystran.mochat.logic.http.LoginOfflineReplayGateway;
import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.ReplayOfflineMessagesCommand;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

@Singleton
@Primary
@Requires(bean = MessageCommandApiGrpc.MessageCommandApiBlockingStub.class)
public final class GrpcLoginOfflineReplayGateway implements LoginOfflineReplayGateway {
    private final MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub;

    public GrpcLoginOfflineReplayGateway(MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub) {
        this.messageCommandApiBlockingStub =
            Objects.requireNonNull(messageCommandApiBlockingStub, "messageCommandApiBlockingStub");
    }

    @Override
    public void replayOnLogin(long userId, String sessionId, long sessionVersion) {
        var ack = messageCommandApiBlockingStub.replayOfflineMessages(ReplayOfflineMessagesCommand.newBuilder()
            .setUserId(userId)
            .setSessionId(sessionId)
            .setSessionVersion(sessionVersion)
            .setMaxBatchSize(OfflineReplayService.MAX_REPLAY_ITEMS)
            .build());
        if (!ack.getAccepted()) {
            throw new IllegalStateException("offline replay rejected: " + ack.getDetail());
        }
    }
}
