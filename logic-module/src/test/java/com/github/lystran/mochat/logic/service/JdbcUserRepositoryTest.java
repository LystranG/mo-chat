package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.chat.ReceiptService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcUserRepositoryTest {
    @Test
    void contextResolvesInMemoryRepositoryWhenNoDataSourceBeanExists() {
        try (ApplicationContext context = ApplicationContext.run(java.util.Map.of("spec.name", "user-repository-bean-selection"))) {
            assertEquals(InMemoryUserRepository.class, context.getBean(UserRepository.class).getClass());
        }
    }

    @Test
    void contextResolvesJdbcRepositoryWhenDataSourceAndIdGeneratorBeansExist() {
        try (ApplicationContext context = ApplicationContext.run(
            java.util.Map.of("spec.name", "user-repository-bean-selection", "spec.variant", "jdbc")
        )) {
            assertEquals(JdbcUserRepository.class, context.getBean(UserRepository.class).getClass());
        }
    }

    @Test
    void createSelectsExistingRowWhenInsertConflictsWithoutReturningRow() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement insertStatement = mock(PreparedStatement.class);
        PreparedStatement findStatement = mock(PreparedStatement.class);
        ResultSet insertResultSet = mock(ResultSet.class);
        ResultSet findResultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(insertStatement, findStatement);
        when(insertStatement.executeQuery()).thenReturn(insertResultSet);
        when(findStatement.executeQuery()).thenReturn(findResultSet);
        when(insertResultSet.next()).thenReturn(false);
        when(findResultSet.next()).thenReturn(true);
        when(findResultSet.getLong(1)).thenReturn(500L);
        when(findResultSet.getString(2)).thenReturn("alice");
        when(findResultSet.getBytes(3)).thenReturn(key((byte) 3));

        JdbcUserRepository repository = new JdbcUserRepository(dataSource, () -> 777L);
        UserProfile loaded = repository.create("alice", key((byte) 9));

        assertEquals(500L, loaded.userId());
        assertEquals("alice", loaded.username());
        assertArrayEquals(key((byte) 3), loaded.identityPublicKey());

        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getAllValues().get(0).contains("ON CONFLICT (username) DO NOTHING"));
        assertTrue(sqlCaptor.getAllValues().get(1).contains("WHERE username = ?"));
    }

    private static byte[] key(byte value) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = value;
        }
        return key;
    }

    @Factory
    @Requires(property = "spec.name", value = "user-repository-bean-selection")
    static final class UserRepositoryContextBeans {
        @Singleton
        EventBus eventBus() {
            return new InProcessEventBus();
        }

        @Singleton
        @Replaces(SessionService.class)
        SessionService sessionService() {
            return mock(SessionService.class);
        }

        @Singleton
        @Replaces(MessageIngestService.class)
        MessageIngestService messageIngestService() {
            return mock(MessageIngestService.class);
        }

        @Singleton
        @Replaces(ReceiptService.class)
        ReceiptService receiptService() {
            return mock(ReceiptService.class);
        }

        @Singleton
        @Requires(property = "spec.variant", value = "jdbc")
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Singleton
        @Requires(property = "spec.variant", value = "jdbc")
        IdGenerator idGenerator() {
            return () -> 1L;
        }
    }
}
