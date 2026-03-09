package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.message.contract.MessagePersistencePort;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RocketMqPersistenceConsumerTest {
    private final DefaultMQPushConsumer delegate = mock(DefaultMQPushConsumer.class);
    private final MessagePersistencePort messagePersistencePort = mock(MessagePersistencePort.class);

    private RocketMqPersistenceConsumer persistenceConsumer;

    @BeforeEach
    void setUp() {
        persistenceConsumer = new RocketMqPersistenceConsumer(delegate, messagePersistencePort);
    }

    @Test
    void consumesValidEnvelopeAndPersistsExpectedMessage() throws Exception {
        MessageExt message = message(
            "1|42|6|11|private|100|100|200||2000|cGF5bG9hZA=="
        );

        ConsumeOrderlyStatus status = persistenceConsumer.consumeMessage(List.of(message), null);

        assertEquals(ConsumeOrderlyStatus.SUCCESS, status);
        verify(messagePersistencePort).persist(MessageAcceptedEvent.privateMessage(
            1L,
            42L,
            6L,
            11L,
            100L,
            100L,
            200L,
            2000L,
            "cGF5bG9hZA=="
        ));
    }

    @Test
    void returnsSuspendWhenPersistenceFails() throws Exception {
        MessageExt message = message(
            "2|77|14|12|group|101|||5001|3100|Z3JvdXAtcGF5bG9hZA=="
        );
        MessageAcceptedEvent event = MessageAcceptedEvent.groupMessage(
            2L,
            77L,
            14L,
            12L,
            101L,
            5001L,
            3100L,
            "Z3JvdXAtcGF5bG9hZA=="
        );
        SQLException failure = new SQLException("db unavailable");
        doThrow(failure).when(messagePersistencePort).persist(event);

        ConsumeOrderlyStatus status = persistenceConsumer.consumeMessage(List.of(message), null);

        assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, status);
    }

    @Test
    void returnsSuspendWhenPayloadIsMalformed() throws Exception {
        MessageExt message = message("not|enough|fields");

        ConsumeOrderlyStatus status = persistenceConsumer.consumeMessage(List.of(message), null);

        assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, status);
        verify(messagePersistencePort, never()).persist(any());
    }

    private static MessageExt message(String body) {
        MessageExt message = new MessageExt();
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
