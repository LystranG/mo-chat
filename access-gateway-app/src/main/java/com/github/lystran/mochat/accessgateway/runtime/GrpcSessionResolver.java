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

public final class GrpcSessionResolver implements SessionResolver {
    private final SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub;

    public GrpcSessionResolver(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub");
    }

    @Override
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
    public Optional<ResolvedSession> resolveSession(String sessionId) {
        return resolveAuthority(sessionId).asResolvedSession();
    }

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
