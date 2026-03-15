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
 * 负责把 MQ 里拿到的消息真正写进数据库，并顺手更新会话当前进度。
 */
public final class MqConsumer implements MessagePersistencePort {
    private final DataSource dataSource;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final GroupMessageCache groupMessageCache;

    // 组装落库流程需要的依赖，其中 groupMessageCache 只负责在提交成功后补一下群消息缓存。
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
    // 按统一格式写入一条消息，并返回数据库确认成功后的回执。
    public PersistenceAckEvent persist(MessageAcceptedEvent event) throws SQLException {
        Objects.requireNonNull(event, "event");
        persistMessage(toPersistedMessage(event));
        return new PersistenceAckEvent(event.msgId(), event.conversationId(), event.seq(), event.serverTimeMs());
    }

    // 用一个数据库事务完成“写消息 + 推进会话位置”，提交后再试着补一下群消息缓存。
    public void persistMessage(MessageRepository.PersistedMessage message) throws SQLException {
        Objects.requireNonNull(message, "message");
        boolean committed = false;

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            Throwable failure = null;
            connection.setAutoCommit(false);
            try {
                // 只有“消息正文写进去”和“会话最新位置往前推”一起提交成功，
                // 才能算这条消息真的已经落库完成。
                messageRepository.insert(connection, message);
                conversationRepository.updateLatestState(
                    connection,
                    message.conversationId(),
                    message.seq(),
                    message.serverTsMs()
                );
                connection.commit();
                committed = true;
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        }

        if (committed && isGroupMessage(message)) {
            try {
                groupMessageCache.cache(message);
            } catch (RuntimeException ignored) {
                // 数据库已经写成功了，这里只是顺手补一下群消息缓存，失败也不能影响主流程。
            }
        }
    }

    // 把上游传来的消息对象改造成 JDBC 这边直接能写库的样子。
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

    // 判断这条消息写库后是否还要顺手补进群消息缓存。
    private static boolean isGroupMessage(MessageRepository.PersistedMessage message) {
        return message.groupId() != null && "group".equals(message.kind());
    }

    // 在 finally 里把连接改回自动提交，免得连接池回收时还带着上一次事务状态。
    private static void restoreAutoCommit(Connection connection, boolean autoCommit, Throwable failure) throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException resetException) {
            if (failure != null) {
                failure.addSuppressed(resetException);
                return;
            }
            // 主事务已经结束，这里只是收尾，不要让收尾报错盖住真正的处理结果。
        }
    }

    // 尽量把当前事务回滚掉；如果回滚本身也失败，就把错误挂到原始异常后面。
    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }
}
