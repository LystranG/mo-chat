package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;


/**
 * 在没有真实关系仓储时放宽校验，供最小运行时或测试环境兜底。
 */
@Singleton
@Requires(missingBeans = MessageRelationshipRepository.class)
public final class AllowAllMessageRelationshipRepository implements MessageRelationshipRepository {
    @Override
    // 始终把私聊关系视为可发送。
    public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
        return PrivateMessageState.ACTIVE;
    }

    @Override
    // 始终把群成员资格视为有效。
    public boolean isActiveGroupMember(long groupId, long userId) {
        return true;
    }

    @Override
    // 兜底实现不维护真实群成员列表。
    public List<Long> listActiveGroupMemberIds(long groupId) {
        return List.of();
    }
}
