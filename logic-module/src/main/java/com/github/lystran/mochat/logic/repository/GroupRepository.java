package com.github.lystran.mochat.logic.repository;

import java.util.List;

public interface GroupRepository {
    GroupRow createGroup(long ownerUserId, String name);

    List<GroupRow> listGroups(long userId);

    void leaveGroup(long userId, long groupId);

    void kickMember(long ownerUserId, long groupId, long memberUserId);

    void dissolveGroup(long ownerUserId, long groupId);

    GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign);

    List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId);

    GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision);

    enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    record GroupRow(long groupId, String name, long ownerUserId) {
    }

    record GroupJoinRequestRow(
        long requestId,
        long groupId,
        long fromUserId,
        String sign,
        String status,
        long createdAtEpochMillis,
        Long handledByUserId,
        Long handledAtEpochMillis
    ) {
    }
}
