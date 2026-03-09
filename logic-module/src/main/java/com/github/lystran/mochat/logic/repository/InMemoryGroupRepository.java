package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
@Requires(missingBeans = GroupRepository.class)
public final class InMemoryGroupRepository implements GroupRepository {
    @Override
    public GroupRow createGroup(long ownerUserId, String name) {
        throw new UnsupportedOperationException("group persistence requires datasource-backed repository");
    }

    @Override
    public List<GroupRow> listGroups(long userId) {
        return List.of();
    }

    @Override
    public void leaveGroup(long userId, long groupId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    @Override
    public void kickMember(long ownerUserId, long groupId, long memberUserId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    @Override
    public void dissolveGroup(long ownerUserId, long groupId) {
        throw new UnsupportedOperationException("group lifecycle mutation requires datasource-backed repository");
    }

    @Override
    public GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }

    @Override
    public List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId) {
        return List.of();
    }

    @Override
    public GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }
}
