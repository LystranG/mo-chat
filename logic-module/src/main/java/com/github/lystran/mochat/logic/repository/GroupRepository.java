package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 定义群生命周期、成员关系和入群申请的仓储接口。
 */
public interface GroupRepository {
    // 创建新群。
    GroupRow createGroup(long ownerUserId, String name);

    // 查询用户加入的群列表。
    List<GroupRow> listGroups(long userId);

    // 处理成员主动退群。
    void leaveGroup(long userId, long groupId);

    // 处理 owner 踢人。
    void kickMember(long ownerUserId, long groupId, long memberUserId);

    // 解散指定群。
    void dissolveGroup(long ownerUserId, long groupId);

    // 创建入群申请。
    GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign);

    // 查询群的待处理入群申请。
    List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId);

    // 处理指定入群申请。
    GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision);

    /** 入群申请处理动作。 */
    enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    /** 群基本信息的一条读取结果。 */
    record GroupRow(long groupId, String name, long ownerUserId) {
    }

    /** 入群申请的一条读取结果。 */
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
