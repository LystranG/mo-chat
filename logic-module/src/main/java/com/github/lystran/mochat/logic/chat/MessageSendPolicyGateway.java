package com.github.lystran.mochat.logic.chat;

import java.util.List;

/**
 * 负责查询“这条消息能不能发”和“群里应该发给谁”。
 */
public interface MessageSendPolicyGateway {
    /**
     * 校验这条私聊是否允许发送。
     */
    void validatePrivateMessage(long conversationId, long senderUid, long recipientUid);

    /**
     * 查询这条群消息需要投递给哪些成员。
     */
    List<Long> resolveGroupRecipientUids(long groupId, long senderUid);
}
