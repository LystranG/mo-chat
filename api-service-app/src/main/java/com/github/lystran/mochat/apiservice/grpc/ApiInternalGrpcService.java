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

/**
 * 对内部服务暴露 `api-service` 的 gRPC 能力，主要负责校验 session 和提供发消息前的上下文。
 */
@Singleton
public final class ApiInternalGrpcService extends SessionAuthorityApiGrpc.SessionAuthorityApiImplBase {
    private final SessionService sessionService;
    private final MessageSendPolicyService messageSendPolicyService;

    /**
     * 收下对内 gRPC 服务需要用到的业务组件。
     */
    public ApiInternalGrpcService(SessionService sessionService, MessageSendPolicyService messageSendPolicyService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.messageSendPolicyService = Objects.requireNonNull(messageSendPolicyService, "messageSendPolicyService");
    }

    /**
     * 按 sessionId 查询当前登录状态，并把结果回给网关或消息服务。
     */
    @Override
    public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> responseObserver) {
        // 调用方只把 sessionId 传过来，真正判断这个 session 现在还能不能用，统一由 api-service 自己负责。
        ResolveSessionResponse response = resolveSessionResponse(request.getSessionId());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * 检查私聊发送前的关系限制，比如是不是好友、有没有被拉黑。
     */
    @Override
    public void checkPrivateMessagingPolicy(
        CheckPrivateMessagingPolicyRequest request,
        StreamObserver<CheckPrivateMessagingPolicyResponse> responseObserver
    ) {
        // 这里是内部桥接：其他服务发来查询，真正的好友关系和拉黑判断还是由本地业务层执行。
        PrivateMessagingPolicy policy = toPrivateMessagingPolicy(messageSendPolicyService.checkPrivateMessagingPolicy(
            request.getConversationId(),
            request.getSenderUid(),
            request.getRecipientUid()
        ));
        responseObserver.onNext(CheckPrivateMessagingPolicyResponse.newBuilder().setPolicy(policy).build());
        responseObserver.onCompleted();
    }

    /**
     * 查询群聊发送所需的上下文，比如发消息的人能不能发、群里有哪些成员。
     */
    @Override
    public void getGroupSendContext(
        GetGroupSendContextRequest request,
        StreamObserver<GetGroupSendContextResponse> responseObserver
    ) {
        MessageSendPolicyService.GroupSendContext groupSendContext =
            messageSendPolicyService.getGroupSendContext(request.getGroupId(), request.getSenderUid());
        // 把“能不能发”和“这条群消息要转给哪些成员”一次性回给调用方，避免对方再问第二遍。
        GetGroupSendContextResponse.Builder response = GetGroupSendContextResponse.newBuilder()
            .setEligibility(toGroupSendEligibility(groupSendContext.eligibility()))
            .addAllMemberUids(groupSendContext.memberUids());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    /**
     * 把 `SessionService` 给出的登录结果整理成 gRPC 返回值。
     */
    private ResolveSessionResponse resolveSessionResponse(String sessionId) {
        SessionAuthority authority = sessionService.resolveAuthority(sessionId);
        ResolveSessionResponse.Builder response = ResolveSessionResponse.newBuilder()
            .setStatus(toResolutionStatus(authority.status()));
        // 只有 session 还有效时，才把 userId 和 sessionVersion 一起带回去给调用方继续使用。
        if (authority.userId() > 0L) {
            response.setPrincipal(SessionPrincipal.newBuilder()
                .setSessionId(authority.sessionId())
                .setUserId(authority.userId())
                .setSessionVersion(authority.sessionVersion())
                .build());
        }
        return response.build();
    }

    /**
     * 把代码里的 session 状态换成 gRPC 协议里定义的枚举值。
     */
    private SessionResolutionStatus toResolutionStatus(SessionAuthorityStatus status) {
        return switch (status) {
            case ACTIVE -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE;
            case EXPIRED -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED;
            case REPLACED -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED;
            case INVALID -> SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID;
        };
    }

    /**
     * 把私聊关系检查结果换成对外协议里的固定选项。
     */
    private PrivateMessagingPolicy toPrivateMessagingPolicy(MessageSendPolicyService.PrivateMessagingPolicy policy) {
        return switch (policy) {
            case ALLOWED -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_ALLOWED;
            case NOT_FRIEND -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_NOT_FRIEND;
            case BLOCKED -> PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_BLOCKED;
        };
    }

    /**
     * 把群聊发送资格换成对外协议里的固定选项。
     */
    private GroupSendEligibility toGroupSendEligibility(MessageSendPolicyService.GroupSendEligibility eligibility) {
        return switch (eligibility) {
            case ALLOWED -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED;
            case NOT_MEMBER -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_NOT_MEMBER;
            case GROUP_NOT_FOUND -> GroupSendEligibility.GROUP_SEND_ELIGIBILITY_GROUP_NOT_FOUND;
        };
    }
}
