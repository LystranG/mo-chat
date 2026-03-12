package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.logic.service.MessageSendPolicyService;
import com.github.lystran.mochat.logic.service.SessionService;
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

import java.util.Objects;

@Singleton
public final class ApiInternalGrpcService extends SessionAuthorityApiGrpc.SessionAuthorityApiImplBase {
    private final SessionService sessionService;
    private final MessageSendPolicyService messageSendPolicyService;

    public ApiInternalGrpcService(SessionService sessionService, MessageSendPolicyService messageSendPolicyService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.messageSendPolicyService = Objects.requireNonNull(messageSendPolicyService, "messageSendPolicyService");
    }

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
        PrivateMessagingPolicy policy = toPrivateMessagingPolicy(messageSendPolicyService.checkPrivateMessagingPolicy(
            request.getConversationId(),
            request.getSenderUid(),
            request.getRecipientUid()
        ));
        responseObserver.onNext(CheckPrivateMessagingPolicyResponse.newBuilder().setPolicy(policy).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getGroupSendContext(
        GetGroupSendContextRequest request,
        StreamObserver<GetGroupSendContextResponse> responseObserver
    ) {
        MessageSendPolicyService.GroupSendContext groupSendContext =
            messageSendPolicyService.getGroupSendContext(request.getGroupId(), request.getSenderUid());
        GetGroupSendContextResponse.Builder response = GetGroupSendContextResponse.newBuilder()
            .setEligibility(toGroupSendEligibility(groupSendContext.eligibility()))
            .addAllMemberUids(groupSendContext.memberUids());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private ResolveSessionResponse resolveSessionResponse(String sessionId) {
        SessionAuthority authority = sessionService.resolveAuthority(sessionId);
        ResolveSessionResponse.Builder response = ResolveSessionResponse.newBuilder()
            .setStatus(toResolutionStatus(authority.status()));
        if (authority.userId() > 0L) {
            response.setPrincipal(SessionPrincipal.newBuilder()
                .setSessionId(authority.sessionId())
                .setUserId(authority.userId())
                .setSessionVersion(authority.sessionVersion())
                .build());
        }
        return response.build();
    }

    private SessionResolutionStatus toResolutionStatus(SessionAuthorityStatus status) {
        return switch (status) {
            case ACTIVE -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE;
            case EXPIRED -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED;
            case REPLACED -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED;
            case INVALID -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID;
        };
    }

    private PrivateMessagingPolicy toPrivateMessagingPolicy(MessageSendPolicyService.PrivateMessagingPolicy policy) {
        return switch (policy) {
            case ALLOWED -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_ALLOWED;
            case NOT_FRIEND -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_NOT_FRIEND;
            case BLOCKED -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_BLOCKED;
        };
    }

    private GroupSendEligibility toGroupSendEligibility(MessageSendPolicyService.GroupSendEligibility eligibility) {
        return switch (eligibility) {
            case ALLOWED -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED;
            case NOT_MEMBER -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_NOT_MEMBER;
            case GROUP_NOT_FOUND -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_GROUP_NOT_FOUND;
        };
    }
}
