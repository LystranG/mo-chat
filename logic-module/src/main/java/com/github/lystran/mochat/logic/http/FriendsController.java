package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.FriendsService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
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
 * 提供好友和好友申请相关的 HTTP 接口。
 */
@Controller("/friends")
public final class FriendsController {
    private final FriendsService friendsService;
    private final SessionService sessionService;

    /**
     * 收下好友接口要用到的业务组件。
     */
    public FriendsController(FriendsService friendsService, SessionService sessionService) {
        this.friendsService = Objects.requireNonNull(friendsService, "friendsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /**
     * 查询当前登录用户的好友列表。
     */
    @Get
    public HttpResponse<?> listFriends(@QueryValue String sessionId) {
        // 列表接口只收 sessionId，当前是谁在查由 SessionService 来认。
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new FriendsResponse(friendsService.listFriends(requesterUid.get())));
    }

    /**
     * 发送一条好友申请。
     */
    @Post("/requests")
    public HttpResponse<?> sendFriendRequest(@Body SendFriendRequest request) {
        // 请求体里的 toUserId 表示想加谁，sign 是发给对方看的备注。
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

    /**
     * 查询当前用户发出去的好友申请。
     */
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

    /**
     * 查询当前用户收到的好友申请。
     */
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

    /**
     * 处理一条好友申请，只接受 `accept` 或 `reject` 两种动作。
     */
    @Post("/requests/{requestId}/handle")
    public HttpResponse<?> handleFriendRequest(long requestId, @Body HandleFriendRequest request) {
        if (request == null) {
            return HttpResponse.badRequest(Map.of("message", "request body is required"));
        }

        // 路径里的 requestId 表示要处理哪条申请，请求体里的 sessionId/action 表示“谁来处理、准备怎么处理”。
        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }
        if (request.action() == null || request.action().trim().isEmpty()) {
            return HttpResponse.badRequest(Map.of("message", "action must be accept or reject"));
        }

        try {
            // 先把 HTTP 里传来的动作字符串收成固定枚举，后面业务层只处理明确选项。
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

    /**
     * 删除一位好友。
     */
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

    /**
     * 把一位好友拉黑。
     */
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

    /**
     * 取消拉黑一位好友。
     */
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

    /**
     * 统一返回“session 无效”错误。
     */
    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    /**
     * 把业务层里的好友申请摘要整理成 HTTP 返回体。
     */
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

    /** 发送好友申请的请求体，`sign` 是发给对方看的备注。 */
    @ReflectiveAccess
    public record SendFriendRequest(String sessionId, long toUserId, String sign) {
    }

    /** 处理好友申请的请求体，`action` 只能是 accept 或 reject。 */
    @ReflectiveAccess
    public record HandleFriendRequest(String sessionId, String action) {
    }

    /** 好友列表返回体。 */
    @ReflectiveAccess
    public record FriendsResponse(List<FriendsService.FriendSummary> friends) {
    }

    /** 单条好友申请返回体。 */
    @ReflectiveAccess
    public record FriendRequestResponse(FriendRequestPayload request) {
    }

    /** 多条好友申请返回体。 */
    @ReflectiveAccess
    public record FriendRequestsResponse(List<FriendRequestPayload> requests) {
    }

    /** 好友申请明细。 */
    @ReflectiveAccess
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

    /** 好友关系变更后的返回体。 */
    @ReflectiveAccess
    public record FriendshipMutationResponse(long friendUserId, String status) {
    }
}
