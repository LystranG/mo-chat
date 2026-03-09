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

class JdbcFriendListRepositoryTest {
    @Test
    void listsActiveFriendsWithPeerIdentityAndConversationId() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(200L);
        when(resultSet.getLong(2)).thenReturn(88L);
        when(resultSet.getString(3)).thenReturn("bob");

        JdbcFriendListRepository repository = new JdbcFriendListRepository(dataSource);
        List<FriendListRepository.FriendRow> friends = repository.listActiveFriends(11L);

        assertEquals(1, friends.size());
        assertEquals(200L, friends.get(0).conversationId());
        assertEquals(88L, friends.get(0).userId());
        assertEquals("bob", friends.get(0).username());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("user_friendships"));
        assertTrue(sql.contains("JOIN users"));
        assertTrue(sql.contains("status = 'ok'"));
    }
}
