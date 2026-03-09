package com.github.lystran.mochat.logic.repository;

import java.util.List;

public interface MessageRelationshipRepository {
    PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh);

    boolean isActiveGroupMember(long groupId, long userId);

    List<Long> listActiveGroupMemberIds(long groupId);

    enum PrivateMessageState {
        ACTIVE,
        NOT_FRIEND,
        BLOCKED
    }
}
