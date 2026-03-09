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

@Controller("/groups")
public final class GroupsController {
    private final GroupsService groupsService;
    private final SessionService sessionService;

    public GroupsController(GroupsService groupsService, SessionService sessionService) {
        this.groupsService = Objects.requireNonNull(groupsService, "groupsService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Post
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
    public HttpResponse<?> listGroups(@QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return unauthorized();
        }

        return HttpResponse.ok(new GroupsResponse(groupsService.listGroups(requesterUid.get())));
    }

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

    @Post("/{groupId}/join-requests")
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

    private static HttpResponse<Map<String, String>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "session invalid"));
    }

    public record CreateGroupRequest(String sessionId, String name) {
    }

    public record JoinGroupRequest(String sessionId, String sign) {
    }

    public record HandleGroupJoinRequest(String sessionId, String action) {
    }

    public record GroupResponse(GroupsService.GroupSummary group) {
    }

    public record GroupsResponse(List<GroupsService.GroupSummary> groups) {
    }

    public record GroupMembershipMutationResponse(long groupId, String status) {
    }

    public record GroupMemberMutationResponse(long groupId, long userId, String status) {
    }

    public record GroupLifecycleMutationResponse(long groupId, String status) {
    }

    public record JoinRequestResponse(JoinRequestPayload request) {
    }

    public record JoinRequestsResponse(List<JoinRequestPayload> requests) {
    }

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
