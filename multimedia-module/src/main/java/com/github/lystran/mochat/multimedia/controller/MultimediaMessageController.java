package com.github.lystran.mochat.multimedia.controller;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.MessageIngestRequest;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.multimedia.dto.SendGroupMultimediaRequest;
import com.github.lystran.mochat.multimedia.dto.SendPrivateMultimediaRequest;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.micronaut.http.annotation.*;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * 多媒体消息发送 Controller
 * 
 * 负责接收客户端的多媒体消息请求，构建 Protobuf 消息并发送到消息摄入服务。
 * 支持私聊和群聊两种场景。
 */
@Controller("/messages")
public final class MultimediaMessageController {

    private static final Logger log = LoggerFactory.getLogger(MultimediaMessageController.class);

    private final MessageIngestService messageIngestService;
    private final IdGenerator idGenerator;
    private final SessionService sessionService;

    @Inject
    public MultimediaMessageController(
            MessageIngestService messageIngestService,
            IdGenerator idGenerator,
            SessionService sessionService
    ) {
        this.messageIngestService = Objects.requireNonNull(messageIngestService, "messageIngestService");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /**
     * 发送私聊多媒体消息
     * 
     * @param request 私聊多媒体消息请求
     * @return 发送结果，包含 clientMsgId 和 conversationId
     */
    @Post("/send-multimedia/private")
    public ApiResponse<Map<String, Object>> sendPrivateMultimedia(@Body SendPrivateMultimediaRequest request) {
        log.info("Received private multimedia message request: messageType={}, toUid={}, fileName={}", 
                request.messageType(), request.toUid(), request.fileName());
//校验 sessionId 有效性，获取发送者 UID
        var senderUidOpt = sessionService.resolveUserId(request.sessionId());
        if (senderUidOpt.isEmpty()) {
            log.warn("Invalid session for private multimedia message: sessionId={}", request.sessionId());
            return ApiResponse.fail("invalid session");
        }

        long senderUid = senderUidOpt.get();
        //用雪花算法生成 clientMsgId，计算会话 ID
        long clientMsgId = idGenerator.nextId();
        long conversationId = calculateConversationId(senderUid, request.toUid());

        log.info("Processing private multimedia message: senderUid={}, toUid={}, clientMsgId={}, conversationId={}", 
                senderUid, request.toUid(), clientMsgId, conversationId);

        // 构建包含多媒体元数据的 Protobuf MediaMetadata
        var mediaMetadataBuilder = Mochat.MediaMetadata.newBuilder()
                .setType(convertMediaType(request.messageType()))
                .setMediaUrl(request.mediaUrl())
                .setFileSize(request.fileSize())
                .setMimeType(request.mimeType())
                .setFileName(request.fileName());
//封装媒体信息（URL、大小、类型、文件名）
//可选字段：缩略图、时长、宽高、波形数据
//通过 setMedia() 放入 MessageContent 的 oneof 字段
        if (request.thumbnailUrl() != null && !request.thumbnailUrl().isBlank()) {
            mediaMetadataBuilder.setThumbnailUrl(request.thumbnailUrl());
        }
        if (request.duration() != null) {
            mediaMetadataBuilder.setDuration(request.duration());
        }
        if (request.width() != null) {
            mediaMetadataBuilder.setWidth(request.width());
        }
        if (request.height() != null) {
            mediaMetadataBuilder.setHeight(request.height());
        }
        if (request.waveformData() != null && !request.waveformData().isBlank()) {
            mediaMetadataBuilder.setWaveformData(com.google.protobuf.ByteString.copyFromUtf8(request.waveformData()));
        }

        // 创建 MessageContent，使用 oneof 包装 MediaMetadata
        var messageContent = Mochat.MessageContent.newBuilder()
                .setMedia(mediaMetadataBuilder.build())
                .build();

        // 构建 PrivateMessageReq（使用 repeated MessageContent 支持组合消息）
        var privateMessageReq = Mochat.PrivateMessageReq.newBuilder()
                .setClientMsgId(clientMsgId)
                .setConversationId(conversationId)
                .setToUid(request.toUid())
                .addContents(messageContent)
                .build();

        // 序列化为 Base64，作为 payloadBase64 传入
        String payloadBase64 = Base64.getEncoder().encodeToString(privateMessageReq.toByteArray());

        log.debug("Built Protobuf message: payloadBase64 length={} chars", payloadBase64.length());

        // 创建消息摄入请求（不再需要单独的 multimediaMetadata 参数）
        var ingestRequest = MessageIngestRequest.privateMessage(
                senderUid,
                conversationId,
                clientMsgId,
                Math.min(senderUid, request.toUid()),
                Math.max(senderUid, request.toUid()),
                payloadBase64
        );

        try {
            messageIngestService.ingest(ingestRequest);

            Map<String, Object> responseData = Map.of(
                    "clientMsgId", clientMsgId,
                    "conversationId", conversationId
            );

            log.info("Private multimedia message sent successfully: clientMsgId={}, conversationId={}", 
                    clientMsgId, conversationId);

            return ApiResponse.ok(responseData);

        } catch (Exception e) {
            log.error("Failed to send private multimedia message: clientMsgId={}, error={}", 
                    clientMsgId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 发送群聊多媒体消息
     * 
     * @param request 群聊多媒体消息请求
     * @return 发送结果，包含 clientMsgId 和 conversationId
     */
    @Post("/send-multimedia/group")
    public ApiResponse<Map<String, Object>> sendGroupMultimedia(@Body SendGroupMultimediaRequest request) {
        log.info("Received group multimedia message request: messageType={}, groupId={}, fileName={}", 
                request.messageType(), request.groupId(), request.fileName());

        var senderUidOpt = sessionService.resolveUserId(request.sessionId());
        if (senderUidOpt.isEmpty()) {
            log.warn("Invalid session for group multimedia message: sessionId={}", request.sessionId());
            return ApiResponse.fail("invalid session");
        }

        long senderUid = senderUidOpt.get();
        long clientMsgId = idGenerator.nextId();

        log.info("Processing group multimedia message: senderUid={}, groupId={}, clientMsgId={}, conversationId={}", 
                senderUid, request.groupId(), clientMsgId, request.conversationId());

        // 构建包含多媒体元数据的 Protobuf MediaMetadata
        var mediaMetadataBuilder = Mochat.MediaMetadata.newBuilder()
                .setType(convertMediaType(request.messageType()))
                .setMediaUrl(request.mediaUrl())
                .setFileSize(request.fileSize())
                .setMimeType(request.mimeType())
                .setFileName(request.fileName());

        if (request.thumbnailUrl() != null && !request.thumbnailUrl().isBlank()) {
            mediaMetadataBuilder.setThumbnailUrl(request.thumbnailUrl());
        }
        if (request.duration() != null) {
            mediaMetadataBuilder.setDuration(request.duration());
        }
        if (request.width() != null) {
            mediaMetadataBuilder.setWidth(request.width());
        }
        if (request.height() != null) {
            mediaMetadataBuilder.setHeight(request.height());
        }
        if (request.waveformData() != null && !request.waveformData().isBlank()) {
            mediaMetadataBuilder.setWaveformData(com.google.protobuf.ByteString.copyFromUtf8(request.waveformData()));
        }

        // 创建 MessageContent，使用 oneof 包装 MediaMetadata
        var messageContent = Mochat.MessageContent.newBuilder()
                .setMedia(mediaMetadataBuilder.build())
                .build();

        // 构建 GroupMessageReq（使用 repeated MessageContent 支持组合消息）
        var groupMessageReq = Mochat.GroupMessageReq.newBuilder()
                .setClientMsgId(clientMsgId)
                .setConversationId(request.conversationId())
                .setGroupId(request.groupId())
                .addContents(messageContent)
                .build();

        // 序列化为 Base64，作为 payloadBase64 传入
        String payloadBase64 = Base64.getEncoder().encodeToString(groupMessageReq.toByteArray());

        log.debug("Built Protobuf message: payloadBase64 length={} chars", payloadBase64.length());

        // 创建消息摄入请求（不再需要单独的 multimediaMetadata 参数）
        var ingestRequest = MessageIngestRequest.groupMessage(
                senderUid,
                request.conversationId(),
                clientMsgId,
                request.groupId(),
                payloadBase64
        );

        try {
            //messageIngestService.ingest() 将群聊消息写入 RocketMQ
            messageIngestService.ingest(ingestRequest);

            //构建响应数据
            Map<String, Object> responseData = Map.of(
                    "clientMsgId", clientMsgId,
                    "conversationId", request.conversationId()
            );

            log.info("Group multimedia message sent successfully: clientMsgId={}, conversationId={}", 
                    clientMsgId, request.conversationId());

            return ApiResponse.ok(responseData);

        } catch (Exception e) {
            log.error("Failed to send group multimedia message: clientMsgId={}, error={}", 
                    clientMsgId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 将字符串类型转换为 Protobuf MediaType 枚举
     */
    private Mochat.MediaType convertMediaType(String type) {
        return switch (type) {
            case "image" -> Mochat.MediaType.IMAGE;
            case "video" -> Mochat.MediaType.VIDEO;
            case "audio" -> Mochat.MediaType.AUDIO;
            case "file" -> Mochat.MediaType.FILE;
            default -> throw new IllegalArgumentException("Unknown media type: " + type);
        };
    }

    /**
     * 计算私聊会话 ID（保证 uid1 < uid2）
     */
    private long calculateConversationId(long uid1, long uid2) {
        return uid1 < uid2 ? uid1 * 1_000_000_000L + uid2 : uid2 * 1_000_000_000L + uid1;
    }
}
