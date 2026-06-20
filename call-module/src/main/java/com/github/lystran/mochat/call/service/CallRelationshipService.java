package com.github.lystran.mochat.call.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.lystran.mochat.call.entity.Friendship;
import com.github.lystran.mochat.call.entity.GroupMember;
import com.github.lystran.mochat.call.mapper.FriendshipMapper;
import com.github.lystran.mochat.call.mapper.GroupMemberMapper;

import jakarta.inject.Singleton;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Objects;

/** 通话模块使用的好友和群成员关系查询服务。 */
@Singleton
public final class CallRelationshipService {
    private static final String ACTIVE = "active";
    private static final String OK = "ok";
    private static final String BLOCKED = "blocked";

    private final SqlSessionFactory sqlSessionFactory;

    public CallRelationshipService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
    }

    public enum PrivateRelationshipState {
        ACTIVE,
        BLOCKED,
        NOT_FRIEND
    }

    public PrivateRelationshipState privateRelationshipState(long firstUserId, long secondUserId) {
        long uid1 = Math.min(firstUserId, secondUserId);
        long uid2 = Math.max(firstUserId, secondUserId);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            FriendshipMapper mapper = session.getMapper(FriendshipMapper.class);
            Friendship friendship = mapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                    .eq(Friendship::getUid1, uid1)
                    .eq(Friendship::getUid2, uid2)
            );
            if (friendship == null) {
                return PrivateRelationshipState.NOT_FRIEND;
            }
            return switch (friendship.getStatus()) {
                case OK -> PrivateRelationshipState.ACTIVE;
                case BLOCKED -> PrivateRelationshipState.BLOCKED;
                default -> PrivateRelationshipState.NOT_FRIEND;
            };
        }
    }

    public boolean isActiveGroupMember(long groupId, long userId) {
        if (groupId <= 0L || userId <= 0L) {
            return false;
        }
        try (SqlSession session = sqlSessionFactory.openSession()) {
            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
            Long count = mapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getUserId, userId)
                    .eq(GroupMember::getStatus, ACTIVE)
            );
            return count != null && count > 0L;
        }
    }

    public List<Long> listActiveGroupMemberIds(long groupId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
            return mapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getStatus, ACTIVE)
                    .orderByAsc(GroupMember::getUserId)
            ).stream().map(GroupMember::getUserId).toList();
        }
    }
}
