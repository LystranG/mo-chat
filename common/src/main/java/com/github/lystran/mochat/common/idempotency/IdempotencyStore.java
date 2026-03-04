package com.github.lystran.mochat.common.idempotency;

import java.util.Optional;

public interface IdempotencyStore {
    Optional<StoredSendResult> find(long senderUid, long clientMsgId);

    void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq);

    record StoredSendResult(long msgId, long seq) {
    }
}
