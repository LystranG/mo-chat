package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 好友列表查询仓储。
 */
public interface FriendListRepository {
    /**
     * 列出某个用户当前处于有效状态的好友。
     */
    List<FriendRow> listActiveFriends(long userId);

    /**
     * 好友列表中的单条记录。
     */
    record FriendRow(long conversationId, long userId, String username) {
    }
}
