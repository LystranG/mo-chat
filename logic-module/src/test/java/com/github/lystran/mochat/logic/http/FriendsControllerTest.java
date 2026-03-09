package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.FriendsService;
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

class FriendsControllerTest {
    @Test
    void returnsActiveFriendListForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(friendsService.listFriends(11L)).thenReturn(List.of(new FriendsService.FriendSummary(200L, 88L, "bob")));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.listFriends("session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendsResponse body = (FriendsController.FriendsResponse) response.body();
        assertNotNull(body);
        assertEquals(1, body.friends().size());
        assertEquals(200L, body.friends().get(0).conversationId());
        assertEquals(88L, body.friends().get(0).userId());
        assertEquals("bob", body.friends().get(0).username());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).listFriends(11L);
    }

    @Test
    void rejectsFriendListWhenSessionIsInvalid() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.listFriends("expired-session");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("expired-session");
    }

    @Test
    void sendsFriendRequestWithOpaqueSign() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        FriendsService.FriendRequestSummary summary = new FriendsService.FriendRequestSummary(
            901L,
            11L,
            22L,
            "opaque-base64-sign",
            "pending",
            1_710_000_000_000L,
            null
        );
        when(friendsService.sendFriendRequest(11L, 22L, "opaque-base64-sign")).thenReturn(summary);

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.sendFriendRequest(new FriendsController.SendFriendRequest("session-ok", 22L, "opaque-base64-sign"));

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendRequestResponse body = (FriendsController.FriendRequestResponse) response.body();
        assertNotNull(body);
        assertEquals(901L, body.request().requestId());
        assertEquals("opaque-base64-sign", body.request().sign());
        assertEquals("pending", body.request().status());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).sendFriendRequest(11L, 22L, "opaque-base64-sign");
    }

    @Test
    void listsSentFriendRequestsForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(friendsService.listSentFriendRequests(11L)).thenReturn(List.of(new FriendsService.FriendRequestSummary(
            901L,
            11L,
            22L,
            "opaque-base64-sign",
            "pending",
            1_710_000_000_000L,
            null
        )));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.listSentFriendRequests("session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendRequestsResponse body = (FriendsController.FriendRequestsResponse) response.body();
        assertNotNull(body);
        assertEquals(1, body.requests().size());
        assertEquals(901L, body.requests().get(0).requestId());
        assertEquals("opaque-base64-sign", body.requests().get(0).sign());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).listSentFriendRequests(11L);
    }

    @Test
    void listsReceivedFriendRequestsForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(22L));
        when(friendsService.listReceivedFriendRequests(22L)).thenReturn(List.of(new FriendsService.FriendRequestSummary(
            901L,
            11L,
            22L,
            "opaque-base64-sign",
            "pending",
            1_710_000_000_000L,
            null
        )));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.listReceivedFriendRequests("session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendRequestsResponse body = (FriendsController.FriendRequestsResponse) response.body();
        assertNotNull(body);
        assertEquals(1, body.requests().size());
        assertEquals(11L, body.requests().get(0).fromUserId());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).listReceivedFriendRequests(22L);
    }

    @Test
    void handlesFriendRequestAcceptForReceiver() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(22L));
        FriendsService.FriendRequestSummary summary = new FriendsService.FriendRequestSummary(
            901L,
            11L,
            22L,
            "opaque-base64-sign",
            "accepted",
            1_710_000_000_000L,
            1_710_000_123_000L
        );
        when(friendsService.handleFriendRequest(901L, 22L, FriendsService.FriendRequestDecision.ACCEPT)).thenReturn(summary);

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.handleFriendRequest(
            901L,
            new FriendsController.HandleFriendRequest("session-ok", "accept")
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendRequestResponse body = (FriendsController.FriendRequestResponse) response.body();
        assertNotNull(body);
        assertEquals("accepted", body.request().status());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).handleFriendRequest(901L, 22L, FriendsService.FriendRequestDecision.ACCEPT);
    }

    @Test
    void rejectsFriendRequestEndpointsWhenSessionIsInvalid() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var sendResponse = controller.sendFriendRequest(new FriendsController.SendFriendRequest("expired-session", 22L, "opaque-base64-sign"));
        var sentResponse = controller.listSentFriendRequests("expired-session");
        var receivedResponse = controller.listReceivedFriendRequests("expired-session");
        var handleResponse = controller.handleFriendRequest(
            901L,
            new FriendsController.HandleFriendRequest("expired-session", "reject")
        );

        assertEquals(HttpStatus.UNAUTHORIZED, sendResponse.getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, sentResponse.getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, receivedResponse.getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, handleResponse.getStatus());
        verify(sessionService, org.mockito.Mockito.times(4)).resolveUserId("expired-session");
        verify(friendsService, never()).sendFriendRequest(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deletesFriendForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(friendsService.deleteFriend(11L, 22L)).thenReturn(new FriendsService.FriendshipMutationSummary(22L, "deleted"));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.deleteFriend(22L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendshipMutationResponse body = (FriendsController.FriendshipMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(22L, body.friendUserId());
        assertEquals("deleted", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).deleteFriend(11L, 22L);
    }

    @Test
    void blocksFriendForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(friendsService.blockFriend(11L, 22L)).thenReturn(new FriendsService.FriendshipMutationSummary(22L, "blocked"));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.blockFriend(22L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendshipMutationResponse body = (FriendsController.FriendshipMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(22L, body.friendUserId());
        assertEquals("blocked", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).blockFriend(11L, 22L);
    }

    @Test
    void unblocksFriendForRequester() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(friendsService.unblockFriend(11L, 22L)).thenReturn(new FriendsService.FriendshipMutationSummary(22L, "ok"));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.unblockFriend(22L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        FriendsController.FriendshipMutationResponse body = (FriendsController.FriendshipMutationResponse) response.body();
        assertNotNull(body);
        assertEquals(22L, body.friendUserId());
        assertEquals("ok", body.status());
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService).unblockFriend(11L, 22L);
    }


    @Test
    void rejectsNullHandleActionAsBadRequest() {
        FriendsService friendsService = mock(FriendsService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(22L));

        FriendsController controller = new FriendsController(friendsService, sessionService);
        var response = controller.handleFriendRequest(
            901L,
            new FriendsController.HandleFriendRequest("session-ok", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("session-ok");
        verify(friendsService, never()).handleFriendRequest(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()
        );
    }

}
