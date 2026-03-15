package com.github.lystran.mochat.logic.http;

/**
 * 抽象“登录成功后去补发离线消息”这一步，好让本地模式和云原生模式走不同实现。
 */
public interface LoginOfflineReplayGateway {
    /**
     * 在登录成功后，为当前 session 触发一次离线消息补发。
     * `sessionVersion` 表示这次登录确认后的版本号，用来限定“只补发这次登录已经认领到的那批积压消息”。
     */
    void replayOnLogin(long userId, String sessionId, long sessionVersion);
}
