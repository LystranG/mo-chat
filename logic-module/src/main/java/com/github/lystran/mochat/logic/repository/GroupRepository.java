package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 群和群成员关系的读写仓储。
 */
public interface GroupRepository {
    /**
     * 创建一个新群。
     */
    GroupRow createGroup(long ownerUserId, String name);

    /**
     * 列出用户当前所在的群。
     */
    List<GroupRow> listGroups(long userId);

    /**
     * 让一个用户退出群聊。
     */
    void leaveGroup(long userId, long groupId);

    /**
     * 由群主把成员移出群聊。
     */
    void kickMember(long ownerUserId, long groupId, long memberUserId);

    /**
     * 解散一个群。
     */
    void dissolveGroup(long ownerUserId, long groupId);

    /**
     * 创建一条入群申请。
     */
    GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign);

    /**
     * 列出群主当前可处理的入群申请。
     */
    List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId);

    /**
     * 处理一条入群申请。
     */
    GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision);

    /**
     * 入群申请处理动作。
     */
    enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 群基础信息。
     */
    record GroupRow(long groupId, String name, long ownerUserId) {
    }

    /**
     * 入群申请记录。
     */
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
