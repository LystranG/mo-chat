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

/**
 * 提供登录注册入口，并在登录成功后补发离线消息。
 */
@Controller("/auth")
public final class AuthController {
    private final UserService userService;
    private final SessionService sessionService;
    private final OfflineReplayService offlineReplayService;

    // 注入认证、会话和离线重放所需依赖。
    public AuthController(UserService userService, SessionService sessionService, OfflineReplayService offlineReplayService) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.offlineReplayService = Objects.requireNonNull(offlineReplayService, "offlineReplayService");
    }

    @Post("/login")
    // 处理登录或首次注册，并返回可直接用于后续请求的 session。
    public HttpResponse<?> login(@Body LoginRequest loginRequest) {
        try {
            var userProfile = userService.loginOrRegister(loginRequest.username(), loginRequest.publicKey());
            String sessionId = sessionService.issueSession(userProfile.userId());

            try {
                offlineReplayService.replayOnLogin(userProfile.userId());
            } catch (RuntimeException ignored) {
                // 登录与发 session 已经成功，离线重放失败只影响补推效果，不回滚本次登录结果。
            }

            return HttpResponse.ok(new LoginResponse(userProfile.userId(), userProfile.username(), sessionId));
        } catch (AuthValidationException authValidationException) {
            return HttpResponse.badRequest(Map.of("error", authValidationException.getMessage()));
        }
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, @Nullable String publicKey) {
    }

    /** 登录成功响应体。 */
    public record LoginResponse(long userId, String username, String sessionId) {
    }
}
