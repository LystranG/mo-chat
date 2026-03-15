package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolutionException;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * 通过内部 gRPC 调用 api-service，拿到会话是否还有效的权威结果。
 */
public final class GrpcSessionResolver implements SessionResolver {
    private final SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub;

    /**
     * 保存访问会话权威服务的阻塞式客户端。
     */
    public GrpcSessionResolver(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub");
    }

    @Override
    /**
     * 查询会话权威状态，判断这条 session 现在还能不能继续占有连接。
     */
    public SessionAuthority resolveAuthority(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionAuthority.invalid(sessionId);
        }

        try {
            var response = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(sessionId).build());
            if (!response.hasPrincipal()) {
                return SessionAuthority.invalid(sessionId);
            }

            var principal = response.getPrincipal();
            if (principal.getUserId() <= 0L) {
                return SessionAuthority.invalid(sessionId);
            }
            return toSessionAuthority(response.getStatus(), principal.getSessionId(), principal.getUserId(), principal.getSessionVersion());
        } catch (RuntimeException runtimeException) {
            throw new SessionResolutionException("api-service session resolution failed", runtimeException);
        }
    }

    @Override
    /**
     * 把权威结果转成绑定流程更容易消费的已解析会话对象。
     */
    public Optional<ResolvedSession> resolveSession(String sessionId) {
        return resolveAuthority(sessionId).asResolvedSession();
    }

    /**
     * 把 gRPC 枚举状态映射成网关内部统一使用的会话状态。
     */
    private SessionAuthority toSessionAuthority(
        SessionResolutionStatus status,
        String sessionId,
        long userId,
        long sessionVersion
    ) {
        SessionAuthorityStatus authorityStatus = switch (status) {
            case SESSION_RESOLUTION_STATUS_ACTIVE -> SessionAuthorityStatus.ACTIVE;
            case SESSION_RESOLUTION_STATUS_EXPIRED -> SessionAuthorityStatus.EXPIRED;
            case SESSION_RESOLUTION_STATUS_REPLACED -> SessionAuthorityStatus.REPLACED;
            case SESSION_RESOLUTION_STATUS_INVALID,
                SESSION_RESOLUTION_STATUS_UNSPECIFIED,
                UNRECOGNIZED -> SessionAuthorityStatus.INVALID;
        };
        return new SessionAuthority(authorityStatus, sessionId, userId, sessionVersion);
    }
}
