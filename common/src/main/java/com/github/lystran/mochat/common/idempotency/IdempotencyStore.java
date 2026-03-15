package com.github.lystran.mochat.common.idempotency;

import java.util.Optional;

/**
 * 记住客户端消息是否已经处理过，避免重试时重复入链路。
 */
public interface IdempotencyStore {
    /**
     * 查某个发送方的某条客户端消息有没有已经处理过的结果。
     */
    Optional<StoredSendResult> find(long senderUid, long clientMsgId);

    /**
     * 只在第一次处理时记下结果，后续重试不覆盖原值。
     */
    void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq);

    /**
     * 保存一条消息第一次处理成功后拿到的服务端编号和顺序号。
     */
    record StoredSendResult(long msgId, long seq) {
    }
}
