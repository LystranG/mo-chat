package com.github.lystran.mochat.logic.repository;

import java.util.List;

public interface FriendListRepository {
    List<FriendRow> listActiveFriends(long userId);

    record FriendRow(long conversationId, long userId, String username) {
    }
}
