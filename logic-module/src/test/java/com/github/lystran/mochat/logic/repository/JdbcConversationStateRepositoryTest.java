package com.github.lystran.mochat.logic.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcConversationStateRepositoryTest {
    @Test
    void resolvesPrivatePeerLatestReceivedSeqFromRequesterPerspective() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(11L);
        when(resultSet.getLong(2)).thenReturn(22L);
        when(resultSet.getLong(3)).thenReturn(7L);
        when(resultSet.getLong(4)).thenReturn(15L);

        JdbcConversationStateRepository repository = new JdbcConversationStateRepository(dataSource);
        long peerLatestSeq = repository.findPrivatePeerLatestReceivedSeq(300L, 11L).orElseThrow();

        assertEquals(15L, peerLatestSeq);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("JOIN user_friendships"));
    }

    @Test
    void loadsLatestPersistedConversationStateForAnyConversationType() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(301L);
        when(resultSet.getLong(2)).thenReturn(150L);
        when(resultSet.getLong(3)).thenReturn(999999L);

        JdbcConversationStateRepository repository = new JdbcConversationStateRepository(dataSource);
        var state = repository.findConversationLatestState(301L).orElseThrow();

        assertEquals(301L, state.conversationId());
        assertEquals(150L, state.latestSeq());
        assertEquals(999999L, state.latestMessageTime());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("latest_seq"));
        assertTrue(sqlCaptor.getValue().contains("latest_message_time"));
    }
}
