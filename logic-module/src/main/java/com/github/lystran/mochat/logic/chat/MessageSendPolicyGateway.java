package com.github.lystran.mochat.logic.chat;

import java.util.List;

public interface MessageSendPolicyGateway {
    void validatePrivateMessage(long conversationId, long senderUid, long recipientUid);

    List<Long> resolveGroupRecipientUids(long groupId, long senderUid);
}
