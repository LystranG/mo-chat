package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 发送消息前的关系校验仓储。
 */
public interface MessageRelationshipRepository {
    /**
     * 查询一条私聊当前是可发、未加好友还是已被拉黑。
     */
    PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh);

    /**
     * 判断群是否存在。
     */
    boolean groupExists(long groupId);

    /**
     * 判断用户是不是群里的活跃成员。
     */
    boolean isActiveGroupMember(long groupId, long userId);

    /**
     * 列出群里当前所有活跃成员。
     */
    List<Long> listActiveGroupMemberIds(long groupId);

    /**
     * 私聊关系校验结果。
     */
    enum PrivateMessageState {
        ACTIVE,
        NOT_FRIEND,
        BLOCKED
    }
}
