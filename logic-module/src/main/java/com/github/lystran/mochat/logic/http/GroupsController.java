package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.GroupsService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 提供群创建、退群、踢人、解散和入群申请相关的 HTTP 接口。
 */
@Controller("/groups")
public final class GroupsController {
    private final GroupsService groupsService;
    private final SessionService sessionService;

    /**
     * 收下群接口要用到的业务组件。
     */
    public GroupsController(GroupsService groupsService, SessionService sessionService) {
        this.groupsService = Objects.requireNonNull(groupsService, "groupsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /**
     * 创建一个新群。
     */
    @Post
    public HttpResponse<?> createGroup(@Body CreateGroupRequest request) {
        // 创建群时不让客户端直接指定“创建人是谁”，而是根据 sessionId 反查当前登录用户。
        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            return HttpResponse.ok(new GroupResponse(groupsService.createGroup(requesterUid.get(), request.name())));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 查询当前用户加入的群列表。
     */
    @Get
    public HttpResponse<?> listGroups(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new GroupsResponse(groupsService.listGroups(requesterUid.get())));
    }

    /**
     * 让当前用户主动退群。
     */
    @Post("/{groupId}/leave")
    public HttpResponse<?> leaveGroup(long groupId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            GroupsService.GroupMembershipMutationSummary summary = groupsService.leaveGroup(requesterUid.get(), groupId);
            return HttpResponse.ok(new GroupMembershipMutationResponse(summary.groupId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 由群主把某个成员踢出群。
     */
    @Post("/{groupId}/members/{memberUserId}/kick")
    public HttpResponse<?> kickMember(long groupId, long memberUserId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            GroupsService.GroupMemberMutationSummary summary = groupsService.kickMember(requesterUid.get(), groupId, memberUserId);
            return HttpResponse.ok(new GroupMemberMutationResponse(summary.groupId(), summary.userId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 由群主解散整个群。
     */
    @Post("/{groupId}/dissolve")
    public HttpResponse<?> dissolveGroup(long groupId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            GroupsService.GroupLifecycleMutationSummary summary = groupsService.dissolveGroup(requesterUid.get(), groupId);
            return HttpResponse.ok(new GroupLifecycleMutationResponse(summary.groupId(), summary.status()));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 发送一条入群申请。
     */
    @Post("/{groupId}/join-requests")
    public HttpResponse<?> sendJoinRequest(long groupId, @Body JoinGroupRequest request) {
        // 路径里的 groupId 表示想申请加入哪个群，sign 是附带给管理员看的备注。
        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            return HttpResponse.ok(new JoinRequestResponse(
                toJoinRequestPayload(groupsService.sendJoinRequest(requesterUid.get(), groupId, request.sign()))
            ));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 查询某个群当前待处理的入群申请。
     */
    @Get("/{groupId}/join-requests")
    public HttpResponse<?> listJoinRequests(long groupId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        try {
            return HttpResponse.ok(new JoinRequestsResponse(
                groupsService.listJoinRequests(requesterUid.get(), groupId).stream()
                    .map(GroupsController::toJoinRequestPayload)
                    .toList()
            ));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 处理一条入群申请，只接受 `accept` 或 `reject` 两种动作。
     */
    @Post("/{groupId}/join-requests/{requestId}/handle")
    public HttpResponse<?> handleJoinRequest(long groupId, long requestId, @Body HandleGroupJoinRequest request) {
        if (request == null) {
            return HttpResponse.badRequest(Map.of("message", "request body is required"));
        }

        // groupId/requestId 说明要处理哪条入群申请，请求体里的 sessionId/action 说明是谁在处理、准备怎么处理。
        var requesterUid = sessionService.resolveUserId(request.sessionId());
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }
        if (request.action() == null || request.action().trim().isEmpty()) {
            return HttpResponse.badRequest(Map.of("message", "action must be accept or reject"));
        }

        try {
            // 先把 HTTP 里的动作字符串收成固定枚举，后面业务层只处理明确选项。
            GroupsService.GroupJoinRequestDecision decision = switch (request.action().trim().toUpperCase(Locale.ROOT)) {
                case "ACCEPT" -> GroupsService.GroupJoinRequestDecision.ACCEPT;
                case "REJECT" -> GroupsService.GroupJoinRequestDecision.REJECT;
                default -> throw new IllegalArgumentException("action must be accept or reject");
            };
            return HttpResponse.ok(new JoinRequestResponse(
                toJoinRequestPayload(groupsService.handleJoinRequest(requesterUid.get(), groupId, requestId, decision))
            ));
        } catch (IllegalArgumentException exception) {
            return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
        }
    }

    /**
     * 把业务层里的入群申请摘要整理成 HTTP 返回体。
     */
    private static JoinRequestPayload toJoinRequestPayload(GroupsService.GroupJoinRequestSummary summary) {
        return new JoinRequestPayload(
            summary.requestId(),
            summary.groupId(),
            summary.fromUserId(),
            summary.sign(),
            summary.status(),
            summary.createdAtEpochMillis(),
            summary.handledByUserId(),
            summary.handledAtEpochMillis()
        );
    }

    /**
     * 统一返回“session 无效”错误。
     */
    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    /** 创建群的请求体。 */
    public record CreateGroupRequest(String sessionId, String name) {
    }

    /** 发送入群申请的请求体，`sign` 是发给管理员看的备注。 */
    public record JoinGroupRequest(String sessionId, String sign) {
    }

    /** 处理入群申请的请求体，`action` 只能是 accept 或 reject。 */
    public record HandleGroupJoinRequest(String sessionId, String action) {
    }

    /** 单个群的返回体。 */
    public record GroupResponse(GroupsService.GroupSummary group) {
    }

    /** 群列表返回体。 */
    public record GroupsResponse(List<GroupsService.GroupSummary> groups) {
    }

    /** 退群后的返回体。 */
    public record GroupMembershipMutationResponse(long groupId, String status) {
    }

    /** 踢人后的返回体。 */
    public record GroupMemberMutationResponse(long groupId, long userId, String status) {
    }

    /** 解散群后的返回体。 */
    public record GroupLifecycleMutationResponse(long groupId, String status) {
    }

    /** 单条入群申请返回体。 */
    public record JoinRequestResponse(JoinRequestPayload request) {
    }

    /** 多条入群申请返回体。 */
    public record JoinRequestsResponse(List<JoinRequestPayload> requests) {
    }

    /** 入群申请明细。 */
    public record JoinRequestPayload(
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
