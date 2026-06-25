package com.github.lystran.mochat.call.controller;

import com.github.lystran.mochat.call.service.CallService;
import com.github.lystran.mochat.call.service.CallSessionResolver;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

import java.util.Map;
import java.util.Objects;


@Controller("/calls")
public final class CallController {
    private final CallService callService;
    private final CallSessionResolver sessionResolver;

    public CallController(CallService callService, CallSessionResolver sessionResolver) {
        this.callService = Objects.requireNonNull(callService, "callService");
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
    }

    @Post("/private/invite")
    public HttpResponse<?> invitePrivateCall(@Body PrivateCallInviteRequest request) {
        var userId = resolveSession(request.sessionId());
        if (userId.isEmpty()) {
            return unauthorized();
        }
        try {
            return HttpResponse.ok(ApiResponse.ok(callService.invitePrivateCall(userId.get(), request.toUserId())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return HttpResponse.badRequest(ApiResponse.error(exception.getMessage()));
        }
    }

    @Post("/private/signal")
    public HttpResponse<?> signalPrivateCall(@Body PrivateCallSignalRequest request) {
        var userId = resolveSession(request.sessionId());
        if (userId.isEmpty()) {
            return unauthorized();
        }
        try {
            return HttpResponse.ok(ApiResponse.ok(callService.forwardPrivateSignal(
                userId.get(),
                request.toUserId(),
                request.type(),
                request.roomName()
            )));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return HttpResponse.badRequest(ApiResponse.error(exception.getMessage()));
        }
    }

    @Post("/group/start")
    public HttpResponse<?> startGroupCall(@Body GroupCallStartRequest request) {
        var userId = resolveSession(request.sessionId());
        if (userId.isEmpty()) {
            return unauthorized();
        }
        try {
            return HttpResponse.ok(ApiResponse.ok(callService.startGroupCall(userId.get(), request.groupId())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return HttpResponse.badRequest(ApiResponse.error(exception.getMessage()));
        }
    }

    @Post("/group/join")
    public HttpResponse<?> joinGroupCall(@Body GroupJoinRequest request) {
        var userId = resolveSession(request.sessionId());
        if (userId.isEmpty()) {
            return unauthorized();
        }
        try {
            return HttpResponse.ok(ApiResponse.ok(callService.joinGroupCall(userId.get(), request.roomName())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return HttpResponse.badRequest(ApiResponse.error(exception.getMessage()));
        }
    }

    @Post("/group/leave")
    public HttpResponse<?> leaveGroupCall(@Body GroupLeaveRequest request) {
        var userId = resolveSession(request.sessionId());
        if (userId.isEmpty()) {
            return unauthorized();
        }
        try {
            callService.leaveGroupCall(userId.get(), request.roomName());
            return HttpResponse.ok(ApiResponse.ok(Map.of("left", true)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return HttpResponse.badRequest(ApiResponse.error(exception.getMessage()));
        }
    }

    private java.util.Optional<Long> resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.Optional.empty();
        }
        return sessionResolver.resolveUserId(sessionId);
    }

    private static HttpResponse<ApiResponse<Void>> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("session invalid"));
    }

    public record ApiResponse<T>(boolean success, T data, String message) {
        public static <T> ApiResponse<T> ok(T data) {
            return new ApiResponse<>(true, data, null);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, null, message);
        }
    }

    public record PrivateCallInviteRequest(String sessionId, long toUserId) {
    }

    public record PrivateCallSignalRequest(String sessionId, long toUserId, String type, String roomName) {
    }

    public record GroupCallStartRequest(String sessionId, long groupId) {
    }

    public record GroupJoinRequest(String sessionId, String roomName) {
    }

    public record GroupLeaveRequest(String sessionId, String roomName) {
    }

}
