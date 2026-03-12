package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;


@Singleton
@Requires(missingBeans = MessageRelationshipRepository.class)
public final class AllowAllMessageRelationshipRepository implements MessageRelationshipRepository {
    @Override
    public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
        return PrivateMessageState.ACTIVE;
    }

    @Override
    public boolean groupExists(long groupId) {
        return true;
    }

    @Override
    public boolean isActiveGroupMember(long groupId, long userId) {
        return true;
    }

    @Override
    public List<Long> listActiveGroupMemberIds(long groupId) {
        return List.of();
    }
}
