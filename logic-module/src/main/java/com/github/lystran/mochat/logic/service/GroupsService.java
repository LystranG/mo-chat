package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.GroupRepository;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 处理建群、退群、踢人、入群申请等群管理操作。
 */
@Singleton
public final class GroupsService {
    private final GroupRepository groupRepository;

    /**
     * 创建群服务。
     */
    public GroupsService(GroupRepository groupRepository) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
    }

    /**
     * 创建一个新群，并返回群基础信息。
     */
    public GroupSummary createGroup(long ownerUserId, String name) {
        requirePositive(ownerUserId, "ownerUserId");
        requireText(name, "name");
        GroupRepository.GroupRow row = groupRepository.createGroup(ownerUserId, name);
        return new GroupSummary(row.groupId(), row.name(), row.ownerUserId());
    }

    /**
     * 查询用户当前所在的群。
     */
    public List<GroupSummary> listGroups(long userId) {
        requirePositive(userId, "userId");
        return groupRepository.listGroups(userId).stream()
            .map(row -> new GroupSummary(row.groupId(), row.name(), row.ownerUserId()))
            .toList();
    }

    /**
     * 让当前用户退出一个群。
     */
    public GroupMembershipMutationSummary leaveGroup(long userId, long groupId) {
        requirePositive(userId, "userId");
        requirePositive(groupId, "groupId");
        groupRepository.leaveGroup(userId, groupId);
        return new GroupMembershipMutationSummary(groupId, "left");
    }

    /**
     * 由群主把某个成员移出群聊。
     */
    public GroupMemberMutationSummary kickMember(long ownerUserId, long groupId, long memberUserId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        requirePositive(memberUserId, "memberUserId");
        groupRepository.kickMember(ownerUserId, groupId, memberUserId);
        return new GroupMemberMutationSummary(groupId, memberUserId, "kicked");
    }

    /**
     * 由群主把自己的好友拉进群。
     */
    public GroupMemberMutationSummary inviteMember(long ownerUserId, long groupId, long memberUserId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        requirePositive(memberUserId, "memberUserId");
        if (ownerUserId == memberUserId) {
            throw new IllegalArgumentException("memberUserId must differ from ownerUserId");
        }
        groupRepository.inviteMember(ownerUserId, groupId, memberUserId);
        return new GroupMemberMutationSummary(groupId, memberUserId, "active");
    }

    /**
     * 解散一个群。
     */
    public GroupLifecycleMutationSummary dissolveGroup(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        groupRepository.dissolveGroup(ownerUserId, groupId);
        return new GroupLifecycleMutationSummary(groupId, "dissolved");
    }

    /**
     * 发起一条入群申请。
     */
    public GroupJoinRequestSummary sendJoinRequest(long requesterUserId, long groupId, String sign) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(groupId, "groupId");
        // sign 由客户端生成并原样透传存库，这里只做是否为空的校验。
        requireText(sign, "sign");
        return toJoinRequestSummary(groupRepository.createJoinRequest(requesterUserId, groupId, sign));
    }

    /**
     * 查询某个群当前待处理的入群申请。
     */
    public List<GroupJoinRequestSummary> listJoinRequests(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        return groupRepository.listJoinRequests(ownerUserId, groupId).stream()
            .map(GroupsService::toJoinRequestSummary)
            .toList();
    }

    /**
     * 由群主处理入群申请。
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
                case ACCEPT -> GroupRepository.GroupJoinRequestDecision.ACCEPT;
                case REJECT -> GroupRepository.GroupJoinRequestDecision.REJECT;
            }
        ));
    }

    /**
     * 把仓储层的入群申请记录整理成 service 返回结构。
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
     * 要求某个 id 必须是正数。
     */
    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * 要求字符串不能为空白。
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    /**
     * 入群申请处理动作。
     */
    public enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    /**
     * 群列表里的单条群摘要。
     */
    public record GroupSummary(long groupId, String name, long ownerUserId) {
    }

    /**
     * 退群操作完成后的结果。
     */
    public record GroupMembershipMutationSummary(long groupId, String status) {
    }

    /**
     * 踢人操作完成后的结果。
     */
    public record GroupMemberMutationSummary(long groupId, long userId, String status) {
    }

    /**
     * 解散群完成后的结果。
     */
    public record GroupLifecycleMutationSummary(long groupId, String status) {
    }

    /**
     * 返回给上层的入群申请摘要。
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
