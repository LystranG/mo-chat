package com.github.lystran.mochat.logic.chat;

/**
 * 负责给发送方回“消息已被服务接受”的确认。
 */
public interface SenderAckPublisher {
    /**
     * 把接受结果发回给发送方，不表示对方已经收到。
     */
    void publishSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs);
}
