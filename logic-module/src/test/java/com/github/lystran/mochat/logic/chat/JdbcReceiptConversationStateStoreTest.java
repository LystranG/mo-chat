package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcReceiptConversationStateStoreTest {
    @Test
    void beanRequiresMakeJdbcStorePrimaryWhenDataSourceExists() {
        Requires jdbcRequires = JdbcReceiptConversationStateStore.class.getAnnotation(Requires.class);
        Requires fallbackRequires = InMemoryReceiptConversationStateStore.class.getAnnotation(Requires.class);

        assertEquals(DataSource.class, jdbcRequires.beans()[0]);
        assertEquals(ReceiptConversationStateStore.class, fallbackRequires.missingBeans()[0]);
    }

    @Test
    void loadsPrivateConversationStateByJoiningConversationAndFriendship() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement findStatement = mock(PreparedStatement.class);
        ResultSet findResultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(findStatement);
        when(findStatement.executeQuery()).thenReturn(findResultSet);
        when(findResultSet.next()).thenReturn(true, false);
        when(findResultSet.getLong(1)).thenReturn(500L);
        when(findResultSet.getLong(2)).thenReturn(11L);
        when(findResultSet.getLong(3)).thenReturn(88L);
        when(findResultSet.getLong(4)).thenReturn(30L);
        when(findResultSet.getLong(5)).thenReturn(9L);
        when(findResultSet.getLong(6)).thenReturn(12L);

        JdbcReceiptConversationStateStore stateStore = new JdbcReceiptConversationStateStore(dataSource);
        ReceiptConversationStateStore.PrivateConversationState state = stateStore.findPrivateConversation(500L).orElseThrow();

        assertEquals(500L, state.conversationId());
        assertEquals(11L, state.uidLow());
        assertEquals(88L, state.uidHigh());
        assertEquals(30L, state.latestSeq());
        assertEquals(9L, state.uidLowSeq());
        assertEquals(12L, state.uidHighSeq());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("JOIN user_friendships"));
    }

    @Test
    void updatesUid2ReceiptSeqUsingDatabaseGreatestResult() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement findStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet findResultSet = mock(ResultSet.class);
        ResultSet updateResultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(findStatement, updateStatement);
        when(findStatement.executeQuery()).thenReturn(findResultSet);
        when(updateStatement.executeQuery()).thenReturn(updateResultSet);

        when(findResultSet.next()).thenReturn(true, false);
        when(findResultSet.getLong(1)).thenReturn(500L);
        when(findResultSet.getLong(2)).thenReturn(11L);
        when(findResultSet.getLong(3)).thenReturn(88L);
        when(findResultSet.getLong(4)).thenReturn(30L);
        when(findResultSet.getLong(5)).thenReturn(7L);
        when(findResultSet.getLong(6)).thenReturn(12L);

        when(updateResultSet.next()).thenReturn(true);
        when(updateResultSet.getLong(1)).thenReturn(12L);

        JdbcReceiptConversationStateStore stateStore = new JdbcReceiptConversationStateStore(dataSource);
        long updatedSeq = stateStore.updateLatestReceivedSeq(500L, 88L, 10L);

        assertEquals(12L, updatedSeq);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sqlCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues();
        assertTrue(sqls.get(1).contains("uid_2_seq = GREATEST"));
    }

}
