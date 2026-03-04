package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import com.github.lystran.mochat.logic.service.AuthValidationException;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.logic.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

import java.util.Map;
import java.util.Objects;

@Controller("/auth")
public final class AuthController {
    private final UserService userService;
    private final SessionService sessionService;
    private final OfflineReplayService offlineReplayService;

    public AuthController(UserService userService, SessionService sessionService, OfflineReplayService offlineReplayService) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.offlineReplayService = Objects.requireNonNull(offlineReplayService, "offlineReplayService");
    }

    @Post("/login")
    public HttpResponse<?> login(@Body LoginRequest loginRequest) {
        try {
            var userProfile = userService.loginOrRegister(loginRequest.username(), loginRequest.publicKey());
            String sessionId = sessionService.issueSession(userProfile.userId());

            try {
                offlineReplayService.replayOnLogin(userProfile.userId());
            } catch (RuntimeException ignored) {
                // Replay is best-effort after successful authentication/session issuance.
            }

            return HttpResponse.ok(new LoginResponse(userProfile.userId(), userProfile.username(), sessionId));
        } catch (AuthValidationException authValidationException) {
            return HttpResponse.badRequest(Map.of("error", authValidationException.getMessage()));
        }
    }

    public record LoginRequest(String username, @Nullable String publicKey) {
    }

    public record LoginResponse(long userId, String username, String sessionId) {
    }
}
