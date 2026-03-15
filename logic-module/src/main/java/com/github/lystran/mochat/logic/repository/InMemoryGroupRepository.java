package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 在没有数据源时拒绝群写操作、对读操作返回空结果的兜底实现。
 */
@Singleton
@Requires(missingBeans = GroupRepository.class)
public final class InMemoryGroupRepository implements GroupRepository {
    @Override
    // 明确告知当前运行时不支持创建群。
    public GroupRow createGroup(long ownerUserId, String name) {
        throw new UnsupportedOperationException("group persistence requires datasource-backed repository");
    }

    @Override
    // 无持久化时返回空群列表。
    public List<GroupRow> listGroups(long userId) {
        return List.of();
    }

    @Override
    // 明确告知当前运行时不支持退群。
    public void leaveGroup(long userId, long groupId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持踢人。
    public void kickMember(long ownerUserId, long groupId, long memberUserId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持解散群。
    public void dissolveGroup(long ownerUserId, long groupId) {
        throw new UnsupportedOperationException("group lifecycle mutation requires datasource-backed repository");
    }

    @Override
    // 明确告知当前运行时不支持落库入群申请。
    public GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }

    @Override
    // 无持久化时返回空入群申请列表。
    public List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId) {
        return List.of();
    }

    @Override
    // 明确告知当前运行时不支持处理入群申请。
    public GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }
}
