package com.github.lystran.mochat.logic.repository;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * 无数据库时使用的群管理兜底实现。
 * 只允许读空列表，写操作直接提示当前模式不支持。
 */
@Singleton
@Requires(missingBeans = GroupRepository.class)
public final class InMemoryGroupRepository implements GroupRepository {
    /**
     * 内存兜底模式不支持建群。
     */
    @Override
    public GroupRow createGroup(long ownerUserId, String name) {
        throw new UnsupportedOperationException("group persistence requires datasource-backed repository");
    }

    /**
     * 内存兜底模式下返回空群列表。
     */
    @Override
    public List<GroupRow> listGroups(long userId) {
        return List.of();
    }

    /**
     * 内存兜底模式不支持退群。
     */
    @Override
    public void leaveGroup(long userId, long groupId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持踢人。
     */
    @Override
    public void kickMember(long ownerUserId, long groupId, long memberUserId) {
        throw new UnsupportedOperationException("group membership mutation requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持解散群。
     */
    @Override
    public void dissolveGroup(long ownerUserId, long groupId) {
        throw new UnsupportedOperationException("group lifecycle mutation requires datasource-backed repository");
    }

    /**
     * 内存兜底模式不支持创建入群申请。
     */
    @Override
    public GroupJoinRequestRow createJoinRequest(long requesterUserId, long groupId, String sign) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }

    /**
     * 内存兜底模式下返回空的入群申请列表。
     */
    @Override
    public List<GroupJoinRequestRow> listJoinRequests(long ownerUserId, long groupId) {
        return List.of();
    }

    /**
     * 内存兜底模式不支持处理入群申请。
     */
    @Override
    public GroupJoinRequestRow handleJoinRequest(long ownerUserId, long groupId, long requestId, GroupJoinRequestDecision decision) {
        throw new UnsupportedOperationException("group join request persistence requires datasource-backed repository");
    }
}
