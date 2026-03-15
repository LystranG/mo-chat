package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.GroupRepository;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 封装群组创建、成员变更和入群申请处理等业务入口。
 */
@Singleton
public final class GroupsService {
    private final GroupRepository groupRepository;

    /**
     * 使用群仓储构造群业务服务。
     */
    public GroupsService(GroupRepository groupRepository) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
    }

    /**
     * 创建新群并返回对外摘要。
     */
    public GroupSummary createGroup(long ownerUserId, String name) {
        requirePositive(ownerUserId, "ownerUserId");
        requireText(name, "name");
        GroupRepository.GroupRow row = groupRepository.createGroup(ownerUserId, name);
        return new GroupSummary(row.groupId(), row.name(), row.ownerUserId());
    }

    /**
     * 列出当前用户可见的群组摘要。
     */
    public List<GroupSummary> listGroups(long userId) {
        requirePositive(userId, "userId");
        return groupRepository.listGroups(userId).stream()
            .map(row -> new GroupSummary(row.groupId(), row.name(), row.ownerUserId()))
            .toList();
    }

    /**
     * 让当前用户主动退出群组。
     */
    public GroupMembershipMutationSummary leaveGroup(long userId, long groupId) {
        requirePositive(userId, "userId");
        requirePositive(groupId, "groupId");
        groupRepository.leaveGroup(userId, groupId);
        return new GroupMembershipMutationSummary(groupId, "left");
    }

    /**
     * 由群主将成员移出群组。
     */
    public GroupMemberMutationSummary kickMember(long ownerUserId, long groupId, long memberUserId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        requirePositive(memberUserId, "memberUserId");
        groupRepository.kickMember(ownerUserId, groupId, memberUserId);
        return new GroupMemberMutationSummary(groupId, memberUserId, "kicked");
    }

    /**
     * 由群主解散整个群组。
     */
    public GroupLifecycleMutationSummary dissolveGroup(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        groupRepository.dissolveGroup(ownerUserId, groupId);
        return new GroupLifecycleMutationSummary(groupId, "dissolved");
    }

    /**
     * 提交入群申请。
     */
    public GroupJoinRequestSummary sendJoinRequest(long requesterUserId, long groupId, String sign) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(groupId, "groupId");
        requireText(sign, "sign");
        return toJoinRequestSummary(groupRepository.createJoinRequest(requesterUserId, groupId, sign));
    }

    /**
     * 列出群主视角下待处理或历史入群申请。
     */
    public List<GroupJoinRequestSummary> listJoinRequests(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        return groupRepository.listJoinRequests(ownerUserId, groupId).stream()
            .map(GroupsService::toJoinRequestSummary)
            .toList();
    }

    /**
     * 由群主审批入群申请。
     */
    public GroupJoinRequestSummary handleJoinRequest(
        long ownerUserId,
        long groupId,
        long requestId,
        GroupJoinRequestDecision decision
    ) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        requirePositive(requestId, "requestId");
        Objects.requireNonNull(decision, "decision");
        return toJoinRequestSummary(groupRepository.handleJoinRequest(
            ownerUserId,
            groupId,
            requestId,
            switch (decision) {
                // 服务层只暴露精简枚举，再在这里映射到底层仓储的决策类型。
                case ACCEPT -> GroupRepository.GroupJoinRequestDecision.ACCEPT;
                case REJECT -> GroupRepository.GroupJoinRequestDecision.REJECT;
            }
        ));
    }

    /**
     * 将仓储层入群申请记录转换为服务层摘要。
     */
    private static GroupJoinRequestSummary toJoinRequestSummary(GroupRepository.GroupJoinRequestRow row) {
        return new GroupJoinRequestSummary(
            row.requestId(),
            row.groupId(),
            row.fromUserId(),
            row.sign(),
            row.status(),
            row.createdAtEpochMillis(),
            row.handledByUserId(),
            row.handledAtEpochMillis()
        );
    }

    /**
     * 校验数值参数必须为正数。
     */
    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * 校验文本参数必须提供有效内容。
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    /**
     * 表示群主对入群申请的审批结果。
     */
    public enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 表示群组基础信息摘要。
     */
    public record GroupSummary(long groupId, String name, long ownerUserId) {
    }

    /**
     * 表示退群操作的结果摘要。
     */
    public record GroupMembershipMutationSummary(long groupId, String status) {
    }

    /**
     * 表示踢人操作的结果摘要。
     */
    public record GroupMemberMutationSummary(long groupId, long userId, String status) {
    }

    /**
     * 表示解散群组操作的结果摘要。
     */
    public record GroupLifecycleMutationSummary(long groupId, String status) {
    }

    /**
     * 表示入群申请的服务层视图。
     */
    public record GroupJoinRequestSummary(
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
