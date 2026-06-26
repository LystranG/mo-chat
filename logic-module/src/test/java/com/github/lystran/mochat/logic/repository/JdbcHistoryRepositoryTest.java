package com.github.lystran.mochat.logic.repository;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcHistoryRepositoryTest {
    @Test
    void queriesConversationWindowUsingSeqDescAndLimit() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(119L);
        when(resultSet.getLong(2)).thenReturn(5001L);
        when(resultSet.getLong(3)).thenReturn(7L);
        when(resultSet.getLong(4)).thenReturn(123456L);
        when(resultSet.getString(5)).thenReturn("payload");

        JdbcHistoryRepository repository = new JdbcHistoryRepository(dataSource);
        List<HistoryRepository.HistoryMessage> messages = repository.findHistory(200L, 120L, 20);

        assertEquals(1, messages.size());
        assertEquals(119L, messages.getFirst().seq());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("conversation_id = ?"));
        assertTrue(sql.contains("seq < ?"));
        assertTrue(sql.contains("ORDER BY seq DESC"));
    }
}
