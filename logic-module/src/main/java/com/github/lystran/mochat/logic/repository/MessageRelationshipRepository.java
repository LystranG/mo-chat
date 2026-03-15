package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 定义私聊关系与群成员资格校验接口。
 */
public interface MessageRelationshipRepository {
    // 查询私聊会话当前的关系状态。
    PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh);

    // 判断用户是否是群的活跃成员。
    boolean isActiveGroupMember(long groupId, long userId);

    // 列出群内当前活跃成员 ID。
    List<Long> listActiveGroupMemberIds(long groupId);

    /** 私聊关系状态枚举。 */
    enum PrivateMessageState {
        ACTIVE,
        NOT_FRIEND,
        BLOCKED
    }
}
