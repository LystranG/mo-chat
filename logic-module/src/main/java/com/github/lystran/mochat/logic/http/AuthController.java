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
 * 提供登录入口：先登录或注册，再签发 session，并在登录成功后顺手触发离线消息补发。
 */
@Controller("/auth")
public final class AuthController {
    private final UserService userService;
    private final SessionService sessionService;
    private final LoginOfflineReplayGateway loginOfflineReplayGateway;

    /**
     * 收下登录接口要用到的用户、session 和离线补发组件。
     */
    public AuthController(
        UserService userService,
        SessionService sessionService,
        LoginOfflineReplayGateway loginOfflineReplayGateway
    ) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.loginOfflineReplayGateway = Objects.requireNonNull(loginOfflineReplayGateway, "loginOfflineReplayGateway");
    }

    /**
     * 处理登录请求：需要时新建用户，签发 session，并在成功后尝试补发离线消息。
     */
    @Post("/login")
    public HttpResponse<?> login(@Body LoginRequest loginRequest) {
        try {
            // 这里只收登录参数，至于这是老用户登录还是首次注册，交给 UserService 自己判断。
            var userProfile = userService.loginOrRegister(loginRequest.username(), loginRequest.publicKey());
            String sessionId = sessionService.issueSession(userProfile.userId());
            // 登录刚成功时顺手把当前 sessionVersion 取出来，后面补发离线消息时要带上它。
            long sessionVersion = sessionService.resolveAuthority(sessionId).sessionVersion();

            try {
                loginOfflineReplayGateway.replayOnLogin(userProfile.userId(), sessionId, sessionVersion);
            } catch (RuntimeException ignored) {
                // 登录和发 session 已经成功了；这里补发离线消息失败，不应该把这次登录整个判成失败。
            }

            return HttpResponse.ok(new LoginResponse(userProfile.userId(), userProfile.username(), sessionId));
        } catch (AuthValidationException authValidationException) {
            return HttpResponse.badRequest(Map.of("error", authValidationException.getMessage()));
        }
    }

    /** 登录请求体，`publicKey` 表示客户端这次顺手上报的公钥。 */
    public record LoginRequest(String username, @Nullable String publicKey) {
    }

    /** 登录成功后的返回体，`sessionId` 是后续调用 HTTP 接口时要带上的登录凭证。 */
    public record LoginResponse(long userId, String username, String sessionId) {
    }
}
