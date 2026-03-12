package com.github.lystran.mochat.logic.chat;

public interface SenderAckPublisher {
    void publishSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs);
}
