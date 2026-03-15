package com.github.lystran.mochat.common.seq;

/**
 * 按会话生成单调递增的消息顺序号。
 */
public interface ConversationSeqGenerator {
    /**
     * 取这个会话的下一个顺序号。
     */
    long next(long conversationId);
}
