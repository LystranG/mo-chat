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

/**
 * 提供好友列表、好友申请和好友关系变更的 HTTP 入口。
 */
@Controller("/friends")
public final class FriendsController {
    private final FriendsService friendsService;
    private final SessionService sessionService;

    // 注入好友业务服务与 session 鉴权依赖。
    public FriendsController(FriendsService friendsService, SessionService sessionService) {
        this.friendsService = Objects.requireNonNull(friendsService, "friendsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Get
    // 查询当前登录用户的好友列表。
    public HttpResponse<?> listFriends(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new FriendsResponse(friendsService.listFriends(requesterUid.get())));
    }

    @Post("/requests")
    // 发起新的好友申请。
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
    // 查询当前用户已发出的好友申请。
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
    // 查询当前用户收到的好友申请。
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
    // 接受或拒绝指定好友申请。
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
            // 这里先把 accept/reject 这样的字符串动作改成固定选项，避免大小写和乱填值继续往下传。
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
    // 删除与指定用户的好友关系。
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
    // 拉黑指定好友。
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
    // 解除对指定好友的拉黑状态。
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

    // 统一返回 session 无效时的 HTTP 响应。
    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    // 把 service 层结果整理成 HTTP 返回内容。
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

    /** 发起好友申请请求体。 */
    public record SendFriendRequest(String sessionId, long toUserId, String sign) {
    }

    /** 处理好友申请请求体。 */
    public record HandleFriendRequest(String sessionId, String action) {
    }

    /** 好友列表响应体。 */
    public record FriendsResponse(List<FriendsService.FriendSummary> friends) {
    }

    /** 单条好友申请响应体。 */
    public record FriendRequestResponse(FriendRequestPayload request) {
    }

    /** 好友申请列表响应体。 */
    public record FriendRequestsResponse(List<FriendRequestPayload> requests) {
    }

    /** 好友申请的返回内容。 */
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

    /** 好友关系变更响应体。 */
    public record FriendshipMutationResponse(long friendUserId, String status) {
    }
}
