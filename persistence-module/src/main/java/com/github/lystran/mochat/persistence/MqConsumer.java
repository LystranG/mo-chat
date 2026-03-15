package com.github.lystran.mochat.persistence;

import com.github.lystran.mochat.message.contract.MessageAcceptedEvent;
import com.github.lystran.mochat.message.contract.MessagePersistencePort;
import com.github.lystran.mochat.message.contract.PersistenceAckEvent;
import com.github.lystran.mochat.persistence.cache.GroupMessageCache;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 收到 RocketMQ 里的消息后，负责真正把它写进数据库。
 */
public final class MqConsumer implements MessagePersistencePort {
    private final DataSource dataSource;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final GroupMessageCache groupMessageCache;

    /**
     * 收下落库要用到的数据库和仓储依赖。
     */
    public MqConsumer(
        DataSource dataSource,
        MessageRepository messageRepository,
        ConversationRepository conversationRepository,
        GroupMessageCache groupMessageCache
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.groupMessageCache = Objects.requireNonNull(groupMessageCache, "groupMessageCache");
    }

    @Override
    /**
     * 把 MQ 里的统一消息对象转成落库对象，再执行真正的持久化。
     */
    public PersistenceAckEvent persist(MessageAcceptedEvent event) throws SQLException {
        Objects.requireNonNull(event, "event");
        persistMessage(toPersistedMessage(event));
        return new PersistenceAckEvent(event.msgId(), event.conversationId(), event.seq(), event.serverTimeMs());
    }

    /**
     * 在一个数据库事务里写消息、推进会话最新位置，并在提交后补群缓存。
     */
    public void persistMessage(MessageRepository.PersistedMessage message) throws SQLException {
        Objects.requireNonNull(message, "message");
        // 只有事务真提交了，RocketMQ 这边才应该把这条消息当成处理完成。
        boolean transactionCommitted = false;
        // 数据库里已经有完全相同的消息时，这里会保持 false，避免重复推进最新 seq。
        boolean inserted = false;

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            Throwable failure = null;
            connection.setAutoCommit(false);
            try {
                MessageRepository.InsertResult insertResult = messageRepository.insert(connection, message);
                inserted = insertResult == MessageRepository.InsertResult.INSERTED;
                if (inserted) {
                    // 只有真插入了新消息，才把 conversations 表里的最新消息位置一起往前推。
                    conversationRepository.updateLatestState(
                        connection,
                        message.conversationId(),
                        message.seq(),
                        message.serverTsMs()
                    );
                }
                connection.commit();
                transactionCommitted = true;
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        }

        if (transactionCommitted && inserted && isGroupMessage(message)) {
            try {
                groupMessageCache.cache(message);
            } catch (RuntimeException ignored) {
                // 数据库已经写成功了，这里只是顺手补一下群最近消息缓存，失败也不能影响主流程。
            }
        }
    }

    /**
     * 把 MQ 里统一格式的消息对象改成数据库仓储使用的对象。
     */
    private static MessageRepository.PersistedMessage toPersistedMessage(MessageAcceptedEvent event) {
        return new MessageRepository.PersistedMessage(
            event.msgId(),
            event.conversationId(),
            event.seq(),
            event.clientMsgId(),
            event.kind(),
            event.senderUid(),
            event.peerUidLow(),
            event.peerUidHigh(),
            event.groupId(),
            event.serverTimeMs(),
            event.payloadBase64()
        );
    }

    /**
     * 判断这条消息是不是群消息，只有群消息才需要补群缓存。
     */
    private static boolean isGroupMessage(MessageRepository.PersistedMessage message) {
        return message.groupId() != null && "group".equals(message.kind());
    }

    /**
     * 事务结束后把自动提交开关恢复回去，别让连接池里残留脏状态。
     */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit, Throwable failure) throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException resetException) {
            if (failure != null) {
                failure.addSuppressed(resetException);
                return;
            }
            // 主事务已经成功结束，这里只是收尾；不要让清理失败盖住真正的处理结果。
        }
    }

    /**
     * 尝试回滚当前事务；如果连回滚也失败，就把异常挂到原始错误上。
     */
    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }
}
