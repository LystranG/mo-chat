package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.logic.chat.MessageRejectException;
import com.github.lystran.mochat.logic.chat.MessageSendPolicyGateway;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GroupSendEligibility;
import com.github.lystran.mochat.protocol.internal.api.v1.PrivateMessagingPolicy;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;

import java.util.List;
import java.util.Objects;

/**
 * 通过 gRPC 调用 api-service，查询消息发送规则。
 */
public final class GrpcMessageSendPolicyGateway implements MessageSendPolicyGateway {
    private final SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub;

    /**
     * 创建一个基于 gRPC 的发送规则网关。
     */
    public GrpcMessageSendPolicyGateway(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub");
    }

    /**
     * 远程查询这条私聊是否允许发送。
     */
    @Override
    public void validatePrivateMessage(long conversationId, long senderUid, long recipientUid) {
        // 这里走的是一次同步 gRPC 请求，请 api-service 直接告诉我们这条私聊能不能发。
        PrivateMessagingPolicy policy = stub.checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
            .setConversationId(conversationId)
            .setSenderUid(senderUid)
            .setRecipientUid(recipientUid)
            .build())
            .getPolicy();
        if (policy == PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_ALLOWED) {
            return;
        }
        if (policy == PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_BLOCKED) {
            throw new MessageRejectException(ErrorCode.FRIEND_BLOCKED, "friendship is blocked");
        }
        throw new MessageRejectException(ErrorCode.NOT_FRIEND, "private message requires active friendship");
    }

    /**
     * 远程查询群消息允许发送时的成员列表。
     */
    @Override
    public List<Long> resolveGroupRecipientUids(long groupId, long senderUid) {
        var response = stub.getGroupSendContext(GetGroupSendContextRequest.newBuilder()
            .setGroupId(groupId)
            .setSenderUid(senderUid)
            .build());
        if (response.getEligibility() == GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED) {
            return List.copyOf(response.getMemberUidsList());
        }
        if (response.getEligibility() == GroupSendEligibility.GROUP_SEND_ELIGIBILITY_GROUP_NOT_FOUND) {
            throw new MessageRejectException(ErrorCode.NOT_IN_GROUP, "group not found");
        }
        throw new MessageRejectException(ErrorCode.NOT_IN_GROUP, "sender is not an active group member");
    }
}
