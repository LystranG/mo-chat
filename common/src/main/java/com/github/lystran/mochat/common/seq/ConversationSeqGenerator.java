package com.github.lystran.mochat.common.seq;

public interface ConversationSeqGenerator {
    long next(long conversationId);
}
