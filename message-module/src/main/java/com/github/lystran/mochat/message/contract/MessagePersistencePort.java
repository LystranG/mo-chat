package com.github.lystran.mochat.message.contract;

/**
 * 负责把已接受的消息交给持久化侧落库。
 */
public interface MessagePersistencePort {
    /**
     * 持久化一条已接受的消息，并返回落库结果。
     */
    PersistenceAckEvent persist(MessageAcceptedEvent event) throws Exception;
}
