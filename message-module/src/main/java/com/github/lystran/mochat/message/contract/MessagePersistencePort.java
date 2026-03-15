package com.github.lystran.mochat.message.contract;

/**
 * 约定“把消息写进存储”的最小入口，让逻辑层不用关心底下到底怎么落库。
 */
public interface MessagePersistencePort {
    // 写入一条已经收下的消息，并返回“数据库这边已经处理完”的确认结果。
    PersistenceAckEvent persist(MessageAcceptedEvent event) throws Exception;
}
