package com.github.lystran.mochat.common.seq;

/**
 * 用来给某个会话依次分配下一个顺序号 seq。
 */
public interface ConversationSeqGenerator {
    /**
     * 给指定会话生成下一个顺序号 seq。
     */
    long next(long conversationId);
}
