package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.GroupsService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupsControllerTest {
    @Test
    void createsGroupForRequester() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.createGroup(11L, "dev-group")).thenReturn(new GroupsService.GroupSummary(7001L, "dev-group", 11L));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.createGroup(new GroupsController.CreateGroupRequest("session-ok", "dev-group"));

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.GroupResponse body = (GroupsController.GroupResponse) response.body();
        assertNotNull(body);
        assertEquals(7001L, body.group().groupId());
        assertEquals("dev-group", body.group().name());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).createGroup(11L, "dev-group");
    }

    @Test
    void listsGroupsForRequester() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.listGroups(11L)).thenReturn(List.of(new GroupsService.GroupSummary(7001L, "dev-group", 11L)));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.listGroups("session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.GroupsResponse body = (GroupsController.GroupsResponse) response.body();
        assertNotNull(body);
        assertEquals(1, body.groups().size());
        assertEquals(7001L, body.groups().get(0).groupId());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).listGroups(11L);
    }

    @Test
    void leavesGroupForRequester() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.leaveGroup(11L, 7001L)).thenReturn(new GroupsService.GroupMembershipMutationSummary(7001L, "left"));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.leaveGroup(7001L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.GroupMembershipMutationResponse body = (GroupsController.GroupMembershipMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(7001L, body.groupId());
        assertEquals("left", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).leaveGroup(11L, 7001L);
    }

    @Test
    void kicksMemberForOwner() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.kickMember(11L, 7001L, 22L)).thenReturn(new GroupsService.GroupMemberMutationSummary(7001L, 22L, "kicked"));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.kickMember(7001L, 22L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.GroupMemberMutationResponse body = (GroupsController.GroupMemberMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(7001L, body.groupId());
        assertEquals(22L, body.userId());
        assertEquals("kicked", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).kickMember(11L, 7001L, 22L);
    }

    @Test
    void dissolvesGroupForOwner() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.dissolveGroup(11L, 7001L)).thenReturn(new GroupsService.GroupLifecycleMutationSummary(7001L, "dissolved"));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.dissolveGroup(7001L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.GroupLifecycleMutationResponse body = (GroupsController.GroupLifecycleMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(7001L, body.groupId());
        assertEquals("dissolved", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).dissolveGroup(11L, 7001L);
    }

    @Test
    void sendsJoinRequestWithOpaqueSign() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(22L));
        when(groupsService.sendJoinRequest(22L, 7001L, "opaque-sign")).thenReturn(
            new GroupsService.GroupJoinRequestSummary(9001L, 7001L, 22L, "opaque-sign", "pending", 1L, null, null)
        );

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.sendJoinRequest(7001L, new GroupsController.JoinGroupRequest("session-ok", "opaque-sign"));

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.JoinRequestResponse body = (GroupsController.JoinRequestResponse) response.body();
        assertNotNull(body);
        assertEquals(9001L, body.request().requestId());
        assertEquals("opaque-sign", body.request().sign());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).sendJoinRequest(22L, 7001L, "opaque-sign");
    }

    @Test
    void listsJoinRequestsForOwner() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.listJoinRequests(11L, 7001L)).thenReturn(List.of(
            new GroupsService.GroupJoinRequestSummary(9001L, 7001L, 22L, "opaque-sign", "pending", 1L, null, null)
        ));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.listJoinRequests(7001L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.JoinRequestsResponse body = (GroupsController.JoinRequestsResponse) response.body();
        assertNotNull(body);
        assertEquals(1, body.requests().size());
        assertEquals(22L, body.requests().get(0).fromUserId());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).listJoinRequests(11L, 7001L);
    }

    @Test
    void handlesJoinRequestAcceptForOwner() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(groupsService.handleJoinRequest(11L, 7001L, 9001L, GroupsService.GroupJoinRequestDecision.ACCEPT)).thenReturn(
            new GroupsService.GroupJoinRequestSummary(9001L, 7001L, 22L, "opaque-sign", "accepted", 1L, 11L, 2L)
        );

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.handleJoinRequest(
            7001L,
            9001L,
            new GroupsController.HandleGroupJoinRequest("session-ok", "accept")
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        GroupsController.JoinRequestResponse body = (GroupsController.JoinRequestResponse) response.body();
        assertNotNull(body);
        assertEquals("accepted", body.request().status());
        assertEquals(11L, body.request().handledByUserId());
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).handleJoinRequest(11L, 7001L, 9001L, GroupsService.GroupJoinRequestDecision.ACCEPT);
    }

    @Test
    void rejectsNullJoinHandleActionAsBadRequest() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.handleJoinRequest(
            7001L,
            9001L,
            new GroupsController.HandleGroupJoinRequest("session-ok", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService, never()).handleJoinRequest(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void mapsJoinRequestListValidationErrorsToBadRequest() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(22L));
        when(groupsService.listJoinRequests(22L, 7001L)).thenThrow(new IllegalArgumentException("group owner required"));

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var response = controller.listJoinRequests(7001L, "session-ok");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("session-ok");
        verify(groupsService).listJoinRequests(22L, 7001L);
    }

    @Test
    void rejectsGroupEndpointsWhenSessionIsInvalid() {
        GroupsService groupsService = mock(GroupsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        GroupsController controller = new GroupsController(groupsService, sessionService);
        var createResponse = controller.createGroup(new GroupsController.CreateGroupRequest("expired-session", "dev-group"));
        var listResponse = controller.listGroups("expired-session");
        var leaveResponse = controller.leaveGroup(7001L, "expired-session");
        var kickResponse = controller.kickMember(7001L, 22L, "expired-session");
        var dissolveResponse = controller.dissolveGroup(7001L, "expired-session");
        var sendJoinResponse = controller.sendJoinRequest(7001L, new GroupsController.JoinGroupRequest("expired-session", "opaque-sign"));
        var listJoinResponse = controller.listJoinRequests(7001L, "expired-session");
        var handleJoinResponse = controller.handleJoinRequest(
            7001L,
            9001L,
            new GroupsController.HandleGroupJoinRequest("expired-session", "reject")
        );

        assertEquals(HttpStatus.UNAUTHORIZED, createResponse.getStatus());
        assertTrue(createResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, listResponse.getStatus());
        assertTrue(listResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, leaveResponse.getStatus());
        assertTrue(leaveResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, kickResponse.getStatus());
        assertTrue(kickResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, dissolveResponse.getStatus());
        assertTrue(dissolveResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, sendJoinResponse.getStatus());
        assertTrue(sendJoinResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, listJoinResponse.getStatus());
        assertTrue(listJoinResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, handleJoinResponse.getStatus());
        assertTrue(handleJoinResponse.body() instanceof Map<?, ?>);
        verify(sessionService, org.mockito.Mockito.times(8)).resolveUserId("expired-session");
    }
}
