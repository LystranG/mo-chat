package com.github.lystran.mochat.persistence;

import java.sql.SQLException;

/**
 * 表示数据库里已经有同一个 msgId，但内容和本次要写入的不一致。
 */
public final class DurableMessageConflictException extends SQLException {
    /**
     * 把这次想写入的内容和库里已有内容一起带到异常里，方便定位冲突。
     */
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
