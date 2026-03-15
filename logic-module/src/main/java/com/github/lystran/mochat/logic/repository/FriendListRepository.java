package com.github.lystran.mochat.logic.repository;

import java.util.List;

/**
 * 定义好友列表读取接口。
 */
public interface FriendListRepository {
    // 列出指定用户当前有效的好友关系。
    List<FriendRow> listActiveFriends(long userId);

    /** 好友列表里的一条结果。 */
    record FriendRow(long conversationId, long userId, String username) {
    }
}
