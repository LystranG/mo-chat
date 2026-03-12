package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.logic.chat.MessageRejectException;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageSendPolicyGateway;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.CheckPrivateMessagingPolicyResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.GetGroupSendContextResponse;
import com.github.lystran.mochat.protocol.internal.api.v1.GroupSendEligibility;
import com.github.lystran.mochat.protocol.internal.api.v1.PrivateMessagingPolicy;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcMessageSendPolicyGatewayTest {
    @Test
    void privateBlockedPolicyFailsClosedWithFriendBlockedError() {
        var stub = mock(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
        when(stub.checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
            .setConversationId(200L)
            .setSenderUid(11L)
            .setRecipientUid(88L)
            .build()))
            .thenReturn(CheckPrivateMessagingPolicyResponse.newBuilder()
                .setPolicy(PrivateMessagingPolicy.PRIVATE_MESSAGING_POLICY_BLOCKED)
                .build());

        var gateway = new GrpcMessageSendPolicyGateway(stub);

        var rejection = assertThrows(
            MessageRejectException.class,
            () -> gateway.validatePrivateMessage(200L, 11L, 88L)
        );

        assertEquals(ErrorCode.FRIEND_BLOCKED, rejection.errorCode());
        verify(stub).checkPrivateMessagingPolicy(CheckPrivateMessagingPolicyRequest.newBuilder()
            .setConversationId(200L)
            .setSenderUid(11L)
            .setRecipientUid(88L)
            .build());
    }

    @Test
    void allowedGroupContextReturnsAllMemberUidsFromApiService() {
        var stub = mock(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
        when(stub.getGroupSendContext(GetGroupSendContextRequest.newBuilder()
            .setGroupId(300L)
            .setSenderUid(11L)
            .build()))
            .thenReturn(GetGroupSendContextResponse.newBuilder()
                .setEligibility(GroupSendEligibility.GROUP_SEND_ELIGIBILITY_ALLOWED)
                .addAllMemberUids(List.of(11L, 22L, 33L))
                .build());

        var gateway = new GrpcMessageSendPolicyGateway(stub);

        assertEquals(List.of(11L, 22L, 33L), gateway.resolveGroupRecipientUids(300L, 11L));
        verify(stub).getGroupSendContext(GetGroupSendContextRequest.newBuilder()
            .setGroupId(300L)
            .setSenderUid(11L)
            .build());
    }
}
