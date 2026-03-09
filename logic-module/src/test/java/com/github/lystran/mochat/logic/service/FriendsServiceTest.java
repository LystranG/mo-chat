package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.FriendListRepository;
import com.github.lystran.mochat.logic.repository.FriendshipRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendsServiceTest {
    @Test
    void sendsFriendRequestWithOpaqueSignPassthrough() {
        FriendListRepository friendListRepository = mock(FriendListRepository.class);
        FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
        when(friendshipRepository.createFriendRequest(11L, 22L, "opaque-base64-sign")).thenReturn(
            new FriendshipRepository.FriendRequestRow(
                901L,
                11L,
                22L,
                "opaque-base64-sign",
                "pending",
                1_710_000_000_000L,
                Optional.empty()
            )
        );

        FriendsService service = new FriendsService(friendListRepository, friendshipRepository);
        FriendsService.FriendRequestSummary summary = service.sendFriendRequest(11L, 22L, "opaque-base64-sign");

        assertEquals(901L, summary.requestId());
        assertEquals("opaque-base64-sign", summary.sign());
        assertEquals("pending", summary.status());
        assertEquals(null, summary.handledAtEpochMillis());
        verify(friendshipRepository).createFriendRequest(11L, 22L, "opaque-base64-sign");
    }

    @Test
    void listsSentAndReceivedFriendRequests() {
        FriendListRepository friendListRepository = mock(FriendListRepository.class);
        FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
        when(friendshipRepository.listSentFriendRequests(11L)).thenReturn(List.of(
            new FriendshipRepository.FriendRequestRow(901L, 11L, 22L, "opaque-base64-sign", "pending", 1L, Optional.empty())
        ));
        when(friendshipRepository.listReceivedFriendRequests(22L)).thenReturn(List.of(
            new FriendshipRepository.FriendRequestRow(901L, 11L, 22L, "opaque-base64-sign", "pending", 1L, Optional.empty())
        ));

        FriendsService service = new FriendsService(friendListRepository, friendshipRepository);

        assertEquals(1, service.listSentFriendRequests(11L).size());
        assertEquals(1, service.listReceivedFriendRequests(22L).size());
        verify(friendshipRepository).listSentFriendRequests(11L);
        verify(friendshipRepository).listReceivedFriendRequests(22L);
    }

    @Test
    void handlesAcceptAndRejectFriendRequestActions() {
        FriendListRepository friendListRepository = mock(FriendListRepository.class);
        FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
        when(friendshipRepository.handleFriendRequest(901L, 22L, FriendshipRepository.FriendRequestDecision.ACCEPT)).thenReturn(
            new FriendshipRepository.FriendRequestRow(901L, 11L, 22L, "opaque-base64-sign", "accepted", 1L, Optional.of(2L))
        );
        when(friendshipRepository.handleFriendRequest(902L, 22L, FriendshipRepository.FriendRequestDecision.REJECT)).thenReturn(
            new FriendshipRepository.FriendRequestRow(902L, 11L, 22L, "opaque-base64-sign", "rejected", 1L, Optional.of(3L))
        );

        FriendsService service = new FriendsService(friendListRepository, friendshipRepository);

        FriendsService.FriendRequestSummary accepted = service.handleFriendRequest(
            901L,
            22L,
            FriendsService.FriendRequestDecision.ACCEPT
        );
        FriendsService.FriendRequestSummary rejected = service.handleFriendRequest(
            902L,
            22L,
            FriendsService.FriendRequestDecision.REJECT
        );

        assertEquals("accepted", accepted.status());
        assertEquals(2L, accepted.handledAtEpochMillis());
        assertEquals("rejected", rejected.status());
        assertEquals(3L, rejected.handledAtEpochMillis());
        verify(friendshipRepository).handleFriendRequest(901L, 22L, FriendshipRepository.FriendRequestDecision.ACCEPT);
        verify(friendshipRepository).handleFriendRequest(902L, 22L, FriendshipRepository.FriendRequestDecision.REJECT);
    }

    @Test
    void mutatesFriendshipStatusThroughRepository() {
        FriendListRepository friendListRepository = mock(FriendListRepository.class);
        FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
        FriendsService service = new FriendsService(friendListRepository, friendshipRepository);

        FriendsService.FriendshipMutationSummary deleted = service.deleteFriend(11L, 22L);
        FriendsService.FriendshipMutationSummary blocked = service.blockFriend(11L, 22L);
        FriendsService.FriendshipMutationSummary unblocked = service.unblockFriend(11L, 22L);

        assertEquals("deleted", deleted.status());
        assertEquals("blocked", blocked.status());
        assertEquals("ok", unblocked.status());
        verify(friendshipRepository).deleteFriendship(11L, 22L);
        verify(friendshipRepository).blockFriendship(11L, 22L);
        verify(friendshipRepository).unblockFriend(11L, 22L);
    }

    @Test
    void rejectsBlankSignBeforeTouchingRepository() {
        FriendListRepository friendListRepository = mock(FriendListRepository.class);
        FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
        FriendsService service = new FriendsService(friendListRepository, friendshipRepository);

        assertThrows(IllegalArgumentException.class, () -> service.sendFriendRequest(11L, 22L, " "));
    }
}
