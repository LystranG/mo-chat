package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptServiceTest {
    @Test
    void receiptSeqUpdateUsesMaxOfExistingAndIncomingValue() throws Exception {
        InMemoryReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();
        stateStore.upsertPrivateConversation(500L, 11L, 88L, 20L);
        stateStore.updateLatestReceivedSeq(500L, 88L, 12L);

        RecordingEventBus eventBus = new RecordingEventBus();
        ReceiptService receiptService = new ReceiptService(
            stateStore,
            eventBus,
            Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
        );

        boolean accepted = receiptService.handleClientReceiveAck(88L, 500L, 10L);

        assertTrue(accepted);
        ReceiptConversationStateStore.PrivateConversationState state = stateStore
            .findPrivateConversation(500L)
            .orElseThrow();
        assertEquals(12L, state.uidHighSeq());

        String[] segments = eventBus.events().getFirst().split("\\|", 4);
        Mochat.DeliveredAck deliveredAck = Mochat.DeliveredAck.parseFrom(Base64.getDecoder().decode(segments[3]));
        assertEquals(12L, deliveredAck.getLatestReceivedSeq());
    }

    @Test
    void outOfRangeReceiptIsRejected() {
        InMemoryReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();
        stateStore.upsertPrivateConversation(500L, 11L, 88L, 20L);

        RecordingEventBus eventBus = new RecordingEventBus();
        ReceiptService receiptService = new ReceiptService(
            stateStore,
            eventBus,
            Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
        );

        boolean accepted = receiptService.handleClientReceiveAck(88L, 500L, 21L);

        assertFalse(accepted);
        assertTrue(eventBus.events().isEmpty());
    }

    @Test
    void acceptsAckWhenJdbcStateStoreHasServerKnownSeqAheadOfPersistedSeq() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement firstFindStatement = mock(PreparedStatement.class);
        PreparedStatement secondFindStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet firstFindResultSet = mock(ResultSet.class);
        ResultSet secondFindResultSet = mock(ResultSet.class);
        ResultSet updateResultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(firstFindStatement, secondFindStatement, updateStatement);
        when(firstFindStatement.executeQuery()).thenReturn(firstFindResultSet);
        when(secondFindStatement.executeQuery()).thenReturn(secondFindResultSet);
        when(updateStatement.executeQuery()).thenReturn(updateResultSet);

        mockConversationFindRow(firstFindResultSet, 30L);
        mockConversationFindRow(secondFindResultSet, 30L);
        when(updateResultSet.next()).thenReturn(true);
        when(updateResultSet.getLong(1)).thenReturn(35L);

        JdbcReceiptConversationStateStore stateStore = new JdbcReceiptConversationStateStore(dataSource);
        stateStore.upsertPrivateConversation(500L, 11L, 88L, 35L);

        RecordingEventBus eventBus = new RecordingEventBus();
        ReceiptService receiptService = new ReceiptService(
            stateStore,
            eventBus,
            Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
        );

        boolean accepted = receiptService.handleClientReceiveAck(88L, 500L, 35L);

        assertTrue(accepted);
        assertEquals(1, eventBus.events().size());

        String[] segments = eventBus.events().getFirst().split("\\|", 4);
        Mochat.DeliveredAck deliveredAck = Mochat.DeliveredAck.parseFrom(Base64.getDecoder().decode(segments[3]));
        assertEquals(35L, deliveredAck.getLatestReceivedSeq());
    }

    @Test
    void emitsDeliveredAckToPeerSenderWhenOnline() throws Exception {
        InMemoryReceiptConversationStateStore stateStore = new InMemoryReceiptConversationStateStore();
        stateStore.upsertPrivateConversation(500L, 11L, 88L, 20L);

        RecordingEventBus eventBus = new RecordingEventBus();
        ReceiptService receiptService = new ReceiptService(
            stateStore,
            eventBus,
            Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
        );

        boolean accepted = receiptService.handleClientReceiveAck(88L, 500L, 18L);

        assertTrue(accepted);
        assertEquals(1, eventBus.events().size());
        String[] segments = eventBus.events().getFirst().split("\\|", 4);
        assertEquals("11", segments[0]);
        assertEquals(MsgType.DELIVERED_ACK.name(), segments[1]);
        assertEquals(SerializerType.PROTOBUF.name(), segments[2]);

        Mochat.DeliveredAck deliveredAck = Mochat.DeliveredAck.parseFrom(Base64.getDecoder().decode(segments[3]));
        assertEquals(500L, deliveredAck.getConversationId());
        assertEquals(88L, deliveredAck.getToUid());
        assertEquals(18L, deliveredAck.getLatestReceivedSeq());
        assertEquals(1_700_000_000_000L, deliveredAck.getServerTimeMs());
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<String> events = new ArrayList<>();

        @Override
        public void publish(String topic, String event) {
            events.add(event);
        }

        @Override
        public AutoCloseable subscribe(String topic, java.util.function.Consumer<String> subscriber) {
            return () -> {
            };
        }

        List<String> events() {
            return events;
        }
    }

    private static void mockConversationFindRow(ResultSet resultSet, long latestSeq) throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(500L);
        when(resultSet.getLong(2)).thenReturn(11L);
        when(resultSet.getLong(3)).thenReturn(88L);
        when(resultSet.getLong(4)).thenReturn(latestSeq);
        when(resultSet.getLong(5)).thenReturn(9L);
        when(resultSet.getLong(6)).thenReturn(12L);
    }
}
