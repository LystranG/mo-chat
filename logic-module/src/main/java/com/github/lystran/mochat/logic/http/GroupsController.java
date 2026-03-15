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
 * 提供群创建、成员变更和入群申请管理的 HTTP 入口。
 */
@Controller("/groups")
public final class GroupsController {
    private final GroupsService groupsService;
    private final SessionService sessionService;

    // 注入群组业务服务与 session 鉴权依赖。
    public GroupsController(GroupsService groupsService, SessionService sessionService) {
        this.groupsService = Objects.requireNonNull(groupsService, "groupsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Post
    // 创建新群，并让创建者自动成为 owner。
    public HttpResponse<?> createGroup(@Body CreateGroupRequest request) {
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

    @Get
    // 查询当前用户加入的群列表。
    public HttpResponse<?> listGroups(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new GroupsResponse(groupsService.listGroups(requesterUid.get())));
    }

    @Post("/{groupId}/leave")
    // 让当前用户主动退群。
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

    @Post("/{groupId}/members/{memberUserId}/kick")
    // 让群 owner 踢出指定成员。
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

    @Post("/{groupId}/dissolve")
    // 解散指定群组。
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

    @Post("/{groupId}/join-requests")
    // 发起入群申请。
    public HttpResponse<?> sendJoinRequest(long groupId, @Body JoinGroupRequest request) {
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

    @Get("/{groupId}/join-requests")
    // 查询指定群当前待处理的入群申请。
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

    @Post("/{groupId}/join-requests/{requestId}/handle")
    // 接受或拒绝某条入群申请。
    public HttpResponse<?> handleJoinRequest(long groupId, long requestId, @Body HandleGroupJoinRequest request) {
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
            // 这里先把 accept/reject 这样的字符串动作改成固定选项，避免把随意填写的值带进后面的判断。
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

    // 把 service 层结果整理成 HTTP 返回内容。
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

    // 统一返回 session 无效时的 HTTP 响应。
    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    /** 创建群请求体。 */
    public record CreateGroupRequest(String sessionId, String name) {
    }

    /** 发起入群申请请求体。 */
    public record JoinGroupRequest(String sessionId, String sign) {
    }

    /** 处理入群申请请求体。 */
    public record HandleGroupJoinRequest(String sessionId, String action) {
    }

    /** 单个群信息响应体。 */
    public record GroupResponse(GroupsService.GroupSummary group) {
    }

    /** 群列表响应体。 */
    public record GroupsResponse(List<GroupsService.GroupSummary> groups) {
    }

    /** 群成员关系变更响应体。 */
    public record GroupMembershipMutationResponse(long groupId, String status) {
    }

    /** 踢人操作响应体。 */
    public record GroupMemberMutationResponse(long groupId, long userId, String status) {
    }

    /** 群生命周期变更响应体。 */
    public record GroupLifecycleMutationResponse(long groupId, String status) {
    }

    /** 单条入群申请响应体。 */
    public record JoinRequestResponse(JoinRequestPayload request) {
    }

    /** 入群申请列表响应体。 */
    public record JoinRequestsResponse(List<JoinRequestPayload> requests) {
    }

    /** 入群申请的返回内容。 */
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
