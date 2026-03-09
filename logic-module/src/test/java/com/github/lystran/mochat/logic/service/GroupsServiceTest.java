package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.GroupRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupsServiceTest {
    @Test
    void leavesGroupThroughRepository() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        GroupsService service = new GroupsService(groupRepository);

        GroupsService.GroupMembershipMutationSummary summary = service.leaveGroup(11L, 7001L);

        assertEquals(7001L, summary.groupId());
        assertEquals("left", summary.status());
        verify(groupRepository).leaveGroup(11L, 7001L);
    }

    @Test
    void rejectsNonPositiveLeaveArguments() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        GroupsService service = new GroupsService(groupRepository);

        assertThrows(IllegalArgumentException.class, () -> service.leaveGroup(0L, 7001L));
        assertThrows(IllegalArgumentException.class, () -> service.leaveGroup(11L, 0L));
        assertThrows(IllegalArgumentException.class, () -> service.kickMember(0L, 7001L, 22L));
        assertThrows(IllegalArgumentException.class, () -> service.kickMember(11L, 0L, 22L));
        assertThrows(IllegalArgumentException.class, () -> service.kickMember(11L, 7001L, 0L));
        assertThrows(IllegalArgumentException.class, () -> service.dissolveGroup(0L, 7001L));
        assertThrows(IllegalArgumentException.class, () -> service.dissolveGroup(11L, 0L));
    }

    @Test
    void kicksMemberThroughRepository() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        GroupsService service = new GroupsService(groupRepository);

        GroupsService.GroupMemberMutationSummary summary = service.kickMember(11L, 7001L, 22L);

        assertEquals(7001L, summary.groupId());
        assertEquals(22L, summary.userId());
        assertEquals("kicked", summary.status());
        verify(groupRepository).kickMember(11L, 7001L, 22L);
    }

    @Test
    void dissolvesGroupThroughRepository() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        GroupsService service = new GroupsService(groupRepository);

        GroupsService.GroupLifecycleMutationSummary summary = service.dissolveGroup(11L, 7001L);

        assertEquals(7001L, summary.groupId());
        assertEquals("dissolved", summary.status());
        verify(groupRepository).dissolveGroup(11L, 7001L);
    }

    @Test
    void createsAndListsGroups() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        when(groupRepository.createGroup(11L, "dev-group")).thenReturn(new GroupRepository.GroupRow(7001L, "dev-group", 11L));
        when(groupRepository.listGroups(11L)).thenReturn(List.of(new GroupRepository.GroupRow(7001L, "dev-group", 11L)));

        GroupsService service = new GroupsService(groupRepository);

        assertEquals(7001L, service.createGroup(11L, "dev-group").groupId());
        assertEquals(1, service.listGroups(11L).size());
    }

    @Test
    void sendsListsAndHandlesJoinRequestsThroughRepository() {
        GroupRepository groupRepository = mock(GroupRepository.class);
        when(groupRepository.createJoinRequest(22L, 7001L, "opaque-sign")).thenReturn(
            new GroupRepository.GroupJoinRequestRow(9001L, 7001L, 22L, "opaque-sign", "pending", 1L, null, null)
        );
        when(groupRepository.listJoinRequests(11L, 7001L)).thenReturn(List.of(
            new GroupRepository.GroupJoinRequestRow(9001L, 7001L, 22L, "opaque-sign", "pending", 1L, null, null)
        ));
        when(groupRepository.handleJoinRequest(11L, 7001L, 9001L, GroupRepository.GroupJoinRequestDecision.ACCEPT)).thenReturn(
            new GroupRepository.GroupJoinRequestRow(9001L, 7001L, 22L, "opaque-sign", "accepted", 1L, 11L, 2L)
        );

        GroupsService service = new GroupsService(groupRepository);

        assertEquals("opaque-sign", service.sendJoinRequest(22L, 7001L, "opaque-sign").sign());
        assertEquals(1, service.listJoinRequests(11L, 7001L).size());
        assertEquals(
            "accepted",
            service.handleJoinRequest(11L, 7001L, 9001L, GroupsService.GroupJoinRequestDecision.ACCEPT).status()
        );
        verify(groupRepository).createJoinRequest(22L, 7001L, "opaque-sign");
        verify(groupRepository).listJoinRequests(11L, 7001L);
        verify(groupRepository).handleJoinRequest(11L, 7001L, 9001L, GroupRepository.GroupJoinRequestDecision.ACCEPT);
    }
}
