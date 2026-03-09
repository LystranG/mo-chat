package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.FriendsService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Controller("/friends")
public final class FriendsController {
    private final FriendsService friendsService;
    private final SessionService sessionService;

    public FriendsController(FriendsService friendsService, SessionService sessionService) {
        this.friendsService = Objects.requireNonNull(friendsService, "friendsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Get
    public HttpResponse<?> listFriends(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new FriendsResponse(friendsService.listFriends(requesterUid.get())));
    }

    @Post("/requests")
    public HttpResponse<?> sendFriendRequest(@Body SendFriendRequest request) {
        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            return HttpResponse.ok(new FriendRequestResponse(toPayload(
                friendsService.sendFriendRequest(requesterUid.get(), request.toUserId(), request.sign())
            )));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    @Get("/requests/sent")
    public HttpResponse<?> listSentFriendRequests(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new FriendRequestsResponse(
            friendsService.listSentFriendRequests(requesterUid.get()).stream()
                .map(FriendsController::toPayload)
                .toList()
        ));
    }

    @Get("/requests/received")
    public HttpResponse<?> listReceivedFriendRequests(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new FriendRequestsResponse(
            friendsService.listReceivedFriendRequests(requesterUid.get()).stream()
                .map(FriendsController::toPayload)
                .toList()
        ));
    }

    @Post("/requests/{requestId}/handle")
    public HttpResponse<?> handleFriendRequest(long requestId, @Body HandleFriendRequest request) {
        if (request == null) {
            return HttpResponse.badRequest(Map.of("message", "request body is required"));
        }

        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }
        if (request.action() == null || request.action().trim().isEmpty()) {
            return HttpResponse.badRequest(Map.of("message", "action must be accept or reject"));
        }

        try {
            FriendsService.FriendRequestDecision decision = switch (request.action().trim().toUpperCase(Locale.ROOT)) {
                case "ACCEPT" -> FriendsService.FriendRequestDecision.ACCEPT;
                case "REJECT" -> FriendsService.FriendRequestDecision.REJECT;
                default -> throw new IllegalArgumentException("action must be accept or reject");
            };
            return HttpResponse.ok(new FriendRequestResponse(toPayload(
                friendsService.handleFriendRequest(requestId, requesterUid.get(), decision)
            )));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    @Delete("/{friendUserId}")
    public HttpResponse<?> deleteFriend(long friendUserId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            FriendsService.FriendshipMutationSummary summary = friendsService.deleteFriend(requesterUid.get(), friendUserId);
            return HttpResponse.ok(new FriendshipMutationResponse(summary.friendUserId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    @Post("/{friendUserId}/block")
    public HttpResponse<?> blockFriend(long friendUserId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            FriendsService.FriendshipMutationSummary summary = friendsService.blockFriend(requesterUid.get(), friendUserId);
            return HttpResponse.ok(new FriendshipMutationResponse(summary.friendUserId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    @Post("/{friendUserId}/unblock")
    public HttpResponse<?> unblockFriend(long friendUserId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            FriendsService.FriendshipMutationSummary summary = friendsService.unblockFriend(requesterUid.get(), friendUserId);
            return HttpResponse.ok(new FriendshipMutationResponse(summary.friendUserId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    private static FriendRequestPayload toPayload(FriendsService.FriendRequestSummary summary) {
        return new FriendRequestPayload(
            summary.requestId(),
            summary.fromUserId(),
            summary.toUserId(),
            summary.sign(),
            summary.status(),
            summary.createdAtEpochMillis(),
            summary.handledAtEpochMillis()
        );
    }

    public record SendFriendRequest(String sessionId, long toUserId, String sign) {
    }

    public record HandleFriendRequest(String sessionId, String action) {
    }

    public record FriendsResponse(List<FriendsService.FriendSummary> friends) {
    }

    public record FriendRequestResponse(FriendRequestPayload request) {
    }

    public record FriendRequestsResponse(List<FriendRequestPayload> requests) {
    }

    public record FriendRequestPayload(
        long requestId,
        long fromUserId,
        long toUserId,
        String sign,
        String status,
        long createdAtEpochMillis,
        @Nullable Long handledAtEpochMillis
    ) {
    }

    public record FriendshipMutationResponse(long friendUserId, String status) {
    }
}
