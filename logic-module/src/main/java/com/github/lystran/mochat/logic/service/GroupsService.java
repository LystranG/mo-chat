package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.GroupRepository;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
public final class GroupsService {
    private final GroupRepository groupRepository;

    public GroupsService(GroupRepository groupRepository) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
    }

    public GroupSummary createGroup(long ownerUserId, String name) {
        requirePositive(ownerUserId, "ownerUserId");
        requireText(name, "name");
        GroupRepository.GroupRow row = groupRepository.createGroup(ownerUserId, name);
        return new GroupSummary(row.groupId(), row.name(), row.ownerUserId());
    }

    public List<GroupSummary> listGroups(long userId) {
        requirePositive(userId, "userId");
        return groupRepository.listGroups(userId).stream()
            .map(row -> new GroupSummary(row.groupId(), row.name(), row.ownerUserId()))
            .toList();
    }

    public GroupMembershipMutationSummary leaveGroup(long userId, long groupId) {
        requirePositive(userId, "userId");
        requirePositive(groupId, "groupId");
        groupRepository.leaveGroup(userId, groupId);
        return new GroupMembershipMutationSummary(groupId, "left");
    }

    public GroupMemberMutationSummary kickMember(long ownerUserId, long groupId, long memberUserId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        requirePositive(memberUserId, "memberUserId");
        groupRepository.kickMember(ownerUserId, groupId, memberUserId);
        return new GroupMemberMutationSummary(groupId, memberUserId, "kicked");
    }

    public GroupLifecycleMutationSummary dissolveGroup(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        groupRepository.dissolveGroup(ownerUserId, groupId);
        return new GroupLifecycleMutationSummary(groupId, "dissolved");
    }

    public GroupJoinRequestSummary sendJoinRequest(long requesterUserId, long groupId, String sign) {
        requirePositive(requesterUserId, "requesterUserId");
        requirePositive(groupId, "groupId");
        requireText(sign, "sign");
        return toJoinRequestSummary(groupRepository.createJoinRequest(requesterUserId, groupId, sign));
    }

    public List<GroupJoinRequestSummary> listJoinRequests(long ownerUserId, long groupId) {
        requirePositive(ownerUserId, "ownerUserId");
        requirePositive(groupId, "groupId");
        return groupRepository.listJoinRequests(ownerUserId, groupId).stream()
            .map(GroupsService::toJoinRequestSummary)
            .toList();
    }

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

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    public enum GroupJoinRequestDecision {
        ACCEPT,
        REJECT
    }

    public record GroupSummary(long groupId, String name, long ownerUserId) {
    }

    public record GroupMembershipMutationSummary(long groupId, String status) {
    }

    public record GroupMemberMutationSummary(long groupId, long userId, String status) {
    }

    public record GroupLifecycleMutationSummary(long groupId, String status) {
    }

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
