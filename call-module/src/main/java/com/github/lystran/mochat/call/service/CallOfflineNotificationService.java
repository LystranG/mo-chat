package com.github.lystran.mochat.call.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lystran.mochat.call.dto.CallSignalMessage;
import com.github.lystran.mochat.call.dto.CallRoomName;
import com.github.lystran.mochat.call.entity.CallOfflineNotification;
import com.github.lystran.mochat.call.mapper.CallOfflineNotificationMapper;
import com.github.lystran.mochat.call.manager.CallRoomManager;
import com.github.lystran.mochat.call.mq.CallOfflineNotificationMqProducer;
import com.github.lystran.mochat.call.websocket.CallSignalGateway;
import com.github.lystran.mochat.common.id.IdGenerator;

import jakarta.inject.Singleton;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 处理群通话离线通知的入库和上线补推。 */
@Singleton
public final class CallOfflineNotificationService {
    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final CallRoomManager callRoomManager;
    private final CallRelationshipService relationshipService;
    private final CallSignalGateway signalGateway;
    private final CallOfflineNotificationMqProducer mqProducer;
    private final Clock clock = Clock.systemUTC();

    public CallOfflineNotificationService(
        SqlSessionFactory sqlSessionFactory,
        ObjectMapper objectMapper,
        IdGenerator idGenerator,
        CallRoomManager callRoomManager,
        CallRelationshipService relationshipService,
        CallSignalGateway signalGateway,
        CallOfflineNotificationMqProducer mqProducer
    ) {
        this.sqlSessionFactory = Objects.requireNonNull(sqlSessionFactory, "sqlSessionFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.callRoomManager = Objects.requireNonNull(callRoomManager, "callRoomManager");
        this.relationshipService = Objects.requireNonNull(relationshipService, "relationshipService");
        this.signalGateway = Objects.requireNonNull(signalGateway, "signalGateway");
        this.mqProducer = Objects.requireNonNull(mqProducer, "mqProducer");
    }

    public void enqueue(CallSignalMessage message) {
        CallOfflineNotification notification = new CallOfflineNotification();
        notification.setId(idGenerator.nextId());
        notification.setUserId(message.toUserId());
        notification.setType(message.type());
        notification.setCallId(message.callId());
        notification.setRoomName(message.roomName());
        notification.setGroupId(message.groupId());
        notification.setFromUserId(message.fromUserId());
        notification.setPayloadJson(toJson(message));
        notification.setCreatedAt(OffsetDateTime.now(clock));

        try (SqlSession session = sqlSessionFactory.openSession()) {
            CallOfflineNotificationMapper mapper = session.getMapper(CallOfflineNotificationMapper.class);
            mapper.insert(notification);
            session.commit();
        }
    }


    public void enqueueBatch(List<CallSignalMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (mqProducer.sendBatch(messages)) {
            return;
        }
        // MQ 发送失败，同步兜底
        for (CallSignalMessage message : messages) {
            enqueue(message);
        }
    }

    public List<Long> pushPendingNotifications(long userId) {
        CallRoomName.requirePositive(userId, "userId");
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> deliveredIds = new ArrayList<>();

        try (SqlSession session = sqlSessionFactory.openSession()) {
            CallOfflineNotificationMapper mapper = session.getMapper(CallOfflineNotificationMapper.class);
            List<CallOfflineNotification> pending = mapper.selectList(
                new LambdaQueryWrapper<CallOfflineNotification>()
                    .eq(CallOfflineNotification::getUserId, userId)
                    .isNull(CallOfflineNotification::getDeliveredAt)
                    .orderByAsc(CallOfflineNotification::getCreatedAt)
            );

            for (CallOfflineNotification notification : pending) {
                if (!isStillDeliverable(notification)) {
                    markDelivered(mapper, notification, now);
                    continue;
                }
                if (!signalGateway.sendJsonToUser(notification.getUserId(), notification.getPayloadJson())) {
                    continue;
                }
                markDelivered(mapper, notification, now);
                deliveredIds.add(notification.getId());
            }
            session.commit();
        }
        return List.copyOf(deliveredIds);
    }

    private boolean isStillDeliverable(CallOfflineNotification notification) {
        if (notification.getGroupId() != null && notification.getGroupId() > 0) {
            // 群聊：房间还活跃且用户还是群成员时才补推
            return callRoomManager.isActive(notification.getRoomName(), notification.getCallId())
                && relationshipService.isActiveGroupMember(notification.getGroupId(), notification.getUserId());
        }
        // 私聊：房间已关闭，用户上线后直接推送漏接通知
        return true;
    }

    private void markDelivered(CallOfflineNotificationMapper mapper, CallOfflineNotification notification, OffsetDateTime deliveredAt) {
        notification.setDeliveredAt(deliveredAt);
        mapper.updateById(notification);
    }

    private String toJson(CallSignalMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize call notification", exception);
        }
    }
}
