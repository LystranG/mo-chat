package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

@Singleton
@Requires(missingBeans = LoginOfflineReplayGateway.class)
public final class LocalLoginOfflineReplayGateway implements LoginOfflineReplayGateway {
    private final OfflineReplayService offlineReplayService;

    public LocalLoginOfflineReplayGateway(OfflineReplayService offlineReplayService) {
        this.offlineReplayService = Objects.requireNonNull(offlineReplayService, "offlineReplayService");
    }

    @Override
    public void replayOnLogin(long userId, String sessionId, long sessionVersion) {
        offlineReplayService.replayOnLogin(userId);
    }
}
