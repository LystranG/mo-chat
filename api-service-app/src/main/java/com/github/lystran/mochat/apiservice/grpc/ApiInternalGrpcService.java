package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.GroupSendEligibility;
import com.github.lystran.mochat.protocol.internal.api.v1.PrivateMessagingPolicy;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import com.github.lystran.mochat.protocol.internal.common.v1.SessionPrincipal;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public final class ApiInternalGrpcService extends SessionAuthorityApiGrpc.SessionAuthorityApiImplBase {
    private static final Pattern SESSION_PATTERN = Pattern.compile("^(active|expired|replaced):(\\d+):(\\d+)$");

    @Override
    public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> responseObserver) {
        ResolveSessionResponse response = resolveSessionResponse(request.getSessionId());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void checkPrivateMessagingPolicy(
        CheckPrivateMessagingPolicyRequest request,
        StreamObserver<CheckPrivateMessagingPolicyResponse> responseObserver
    ) {
        PrivateMessagingPolicy policy = request.getSenderUid() > 0 && request.getRecipientUid() > 0
            ? PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_ALLOWED
            : PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_NOT_FRIEND;
        responseObserver.onNext(CheckPrivateMessagingPolicyResponse.newBuilder().setPolicy(policy).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getGroupSendContext(
        GetGroupSendContextRequest request,
        StreamObserver<GetGroupSendContextResponse> responseObserver
    ) {
        GetGroupSendContextResponse.Builder response = GetGroupSendContextResponse.newBuilder();
        if (request.getGroupId() <= 0 || request.getSenderUid() <= 0) {
            response.setEligibility(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_NOT_MEMBER);
        } else {
            response.setEligibility(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED)
                .addMemberUids(request.getSenderUid());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private ResolveSessionResponse resolveSessionResponse(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResolveSessionResponse.newBuilder()
                .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID)
                .build();
        }

        Matcher matcher = SESSION_PATTERN.matcher(sessionId);
        if (!matcher.matches()) {
            return ResolveSessionResponse.newBuilder()
                .setStatus(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID)
                .build();
        }

        return ResolveSessionResponse.newBuilder()
            .setStatus(toResolutionStatus(matcher.group(1)))
            .setPrincipal(SessionPrincipal.newBuilder()
                .setSessionId(sessionId)
                .setUserId(Long.parseLong(matcher.group(2)))
                .setSessionVersion(Long.parseLong(matcher.group(3)))
                .build())
            .build();
    }

    private SessionResolutionStatus toResolutionStatus(String state) {
        return switch (state) {
            case "active" -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE;
            case "expired" -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED;
            case "replaced" -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED;
            default -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID;
        };
    }
}
