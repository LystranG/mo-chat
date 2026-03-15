package com.github.lystran.mochat.common.idempotency;

import java.util.Optional;

/**
 * 记住客户端发消息的结果，避免重试时把同一条消息处理两遍。
 */
public interface IdempotencyStore {
    /**
     * 查这个发送方带来的这条客户端消息，之前是不是已经处理过。
     */
    Optional<StoredSendResult> find(long senderUid, long clientMsgId);

    /**
     * 只有之前没记过时，才把这次发送结果记下来。
     */
    void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq);

    /**
     * 保存一次已接收发送对应的服务端消息 ID 和顺序号。
     */
    record StoredSendResult(long msgId, long seq) {
    }
}
