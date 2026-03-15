package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import com.github.lystran.mochat.logic.http.LoginOfflineReplayGateway;
import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.ReplayOfflineMessagesCommand;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

/**
 * 登录成功后，通过 gRPC 让 `message-service` 去补发这个用户之前没收到的消息。
 */
@Singleton
@Primary
@Requires(bean = MessageCommandApiGrpc.MessageCommandApiBlockingStub.class)
public final class GrpcLoginOfflineReplayGateway implements LoginOfflineReplayGateway {
    private final MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub;

    /**
     * 收下调用 `message-service` 所需的 gRPC 客户端。
     */
    public GrpcLoginOfflineReplayGateway(MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub) {
        this.messageCommandApiBlockingStub =
            Objects.requireNonNull(messageCommandApiBlockingStub, "messageCommandApiBlockingStub");
    }

    /**
     * 登录成功后，请 `message-service` 按当前 sessionVersion 补发离线消息。
     */
    @Override
    public void replayOnLogin(long userId, String sessionId, long sessionVersion) {
        // 这里是远程桥接：api-service 只负责发起请求，真正补发消息的是 message-service。
        var ack = messageCommandApiBlockingStub.replayOfflineMessages(ReplayOfflineMessagesCommand.newBuilder()
            .setUserId(userId)
            .setSessionId(sessionId)
            // 带上这次登录拿到的 sessionVersion，表示只补发这次登录已经确认过身份后的积压消息。
            .setSessionVersion(sessionVersion)
            .setMaxBatchSize(OfflineReplayService.MAX_REPLAY_ITEMS)
            .build());
        // 对方明确拒绝时，直接把失败抛出来，让上层决定这次登录后是否忽略补发失败。
        if (!ack.getAccepted()) {
            throw new IllegalStateException("offline replay rejected: " + ack.getDetail());
        }
    }
}
