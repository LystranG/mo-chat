package com.github.lystran.mochat.persistence;

import java.sql.SQLException;

public final class DurableMessageConflictException extends SQLException {
    public DurableMessageConflictException(
        MessageRepository.PersistedMessage attempted,
        MessageRepository.PersistedMessage existing,
        SQLException cause
    ) {
        super(
            "Durable fact conflict for msgId=%d: attempted=%s existing=%s".formatted(
                attempted.msgId(),
                attempted,
                existing
            ),
            cause.getSQLState(),
            cause.getErrorCode(),
            cause
        );
    }
}
