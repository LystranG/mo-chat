package com.github.lystran.mochat.logic.http;

public interface LoginOfflineReplayGateway {
    void replayOnLogin(long userId, String sessionId, long sessionVersion);
}
