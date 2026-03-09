package com.github.lystran.mochat.message.contract;

public interface MessagePersistencePort {
    PersistenceAckEvent persist(MessageAcceptedEvent event) throws Exception;
}
