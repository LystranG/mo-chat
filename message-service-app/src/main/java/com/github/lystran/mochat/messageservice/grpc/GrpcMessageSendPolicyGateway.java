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

public final class GrpcMessageSendPolicyGateway implements MessageSendPolicyGateway {
    private final SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub;

    public GrpcMessageSendPolicyGateway(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub");
    }

    @Override
    public void validatePrivateMessage(long conversationId, long senderUid, long recipientUid) {
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
