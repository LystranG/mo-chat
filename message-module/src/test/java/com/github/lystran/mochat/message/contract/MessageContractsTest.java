package com.github.lystran.mochat.message.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageContractsTest {
    @Test
    void privateAcceptedEventRequiresPeerRange() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> MessageAcceptedEvent.privateMessage(1L, 2L, 3L, 4L, 5L, null, 8L, 9L, "payload")
        );

        assertEquals("private message requires peerUidLow/peerUidHigh", exception.getMessage());
    }

    @Test
    void groupAcceptedEventRequiresGroupId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> MessageAcceptedEvent.groupMessage(1L, 2L, 3L, 4L, 5L, null, 6L, "payload")
        );

        assertEquals("group message requires groupId", exception.getMessage());
    }

    @Test
    void shardingKeyUsesConversationId() {
        MessageAcceptedEvent event = MessageAcceptedEvent.groupMessage(1L, 42L, 6L, 7L, 8L, 99L, 10L, "payload");

        assertEquals("42", event.shardingKey());
    }

    @Test
    void directRecordConstructionAlsoUsesConversationIdForShardingKey() {
        MessageAcceptedEvent event = new MessageAcceptedEvent(1L, 42L, 6L, 7L, "group", 8L, null, null, 99L, 10L, "payload");

        assertEquals("42", event.shardingKey());
    }
}
