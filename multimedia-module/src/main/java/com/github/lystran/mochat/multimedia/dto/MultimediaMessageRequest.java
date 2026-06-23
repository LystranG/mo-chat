package com.github.lystran.mochat.multimedia.dto;

import io.micronaut.core.annotation.Nullable;

/**
 * 多媒体消息请求，用于 HTTP API 接收客户端发送的多媒体消息
 */
public record MultimediaMessageRequest(
        String sessionId,
        long clientMsgId,
        long conversationId,
        long toUid,
        String messageType,      // image/video/audio/file
        String mediaUrl,
        @Nullable String thumbnailUrl,
        long fileSize,
        String mimeType,
        String fileName,
        @Nullable Integer duration,
        @Nullable Integer width,
        @Nullable Integer height
) {
    public MultimediaMessageRequest {
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("messageType cannot be null or blank");
        }
        if (!isValidMessageType(messageType)) {
            throw new IllegalArgumentException("Invalid message type: " + messageType);
        }
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("mediaUrl cannot be null or blank");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType cannot be null or blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName cannot be null or blank");
        }
    }

    private static boolean isValidMessageType(String type) {
        return "image".equals(type) || "video".equals(type) ||
                "audio".equals(type) || "file".equals(type);
    }
}