package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

/**
 * 兼容单进程运行时的离线补发实现，登录成功后直接调用本地补发服务。
 */
@Singleton
@Requires(missingBeans = LoginOfflineReplayGateway.class)
public final class LocalLoginOfflineReplayGateway implements LoginOfflineReplayGateway {
    private final OfflineReplayService offlineReplayService;

    /**
     * 收下本地离线补发服务。
     */
    public LocalLoginOfflineReplayGateway(OfflineReplayService offlineReplayService) {
        this.offlineReplayService = Objects.requireNonNull(offlineReplayService, "offlineReplayService");
    }

    /**
     * 在单进程模式下，登录成功后直接补发这个用户之前没收到的消息。
     */
    @Override
    public void replayOnLogin(long userId, String sessionId, long sessionVersion) {
        // 本地模式里补发服务就在同一个进程，不需要再把 sessionId/sessionVersion 带去远程服务。
        offlineReplayService.replayOnLogin(userId);
    }
}
