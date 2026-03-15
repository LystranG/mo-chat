package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;


/**
 * 在没有真实关系仓储时兜底放行，主要用于极简启动或测试场景。
 */
@Singleton
@Requires(missingBeans = MessageRelationshipRepository.class)
public final class AllowAllMessageRelationshipRepository implements MessageRelationshipRepository {
    /**
     * 默认把所有私聊都视为允许发送。
     */
    @Override
    public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
        return PrivateMessageState.ACTIVE;
    }

    /**
     * 默认把所有群都视为存在。
     */
    @Override
    public boolean groupExists(long groupId) {
        return true;
    }

    /**
     * 默认把所有用户都视为群内有效成员。
     */
    @Override
    public boolean isActiveGroupMember(long groupId, long userId) {
        return true;
    }

    /**
     * 默认不返回任何群成员列表。
     */
    @Override
    public List<Long> listActiveGroupMemberIds(long groupId) {
        return List.of();
    }
}
