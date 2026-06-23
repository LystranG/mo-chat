package com.github.lystran.mochat.multimedia.dto;

import io.micronaut.core.annotation.Nullable;

/**
 * 群聊多媒体消息发送请求 DTO
 *
 * 用于向群组发送多媒体消息（图片、音频、视频、文件等）。
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * {
 *   "sessionId": "session-abc-123",
 *   "groupId": 5001,
 *   "conversationId": 5001,
 *   "messageType": "audio",
 *   "mediaUrl": "/media/download/media/audio-uuid.mp3",
 *   "thumbnailUrl": null,
 *   "fileSize": 51200,
 *   "mimeType": "audio/mpeg",
 *   "fileName": "voice.mp3",
 *   "duration": 30,
 *   "width": null,
 *   "height": null,
 *   "waveformData": "AAAAAP///wD//wAA..."
 * }
 * }</pre>
 */
public record SendGroupMultimediaRequest(
        /**
         * 会话ID（用于身份验证）
         */
        String sessionId,

        /**
         * 群组ID
         */
        long groupId,

        /**
         * 会话ID（等于 groupId）
         */
        long conversationId,

        /**
         * 消息类型：image、video、audio、file
         */
        String messageType,

        /**
         * 媒体文件URL（来自上传接口返回）
         */
        String mediaUrl,

        /**
         * 缩略图URL（仅图片需要，可选）
         */
        @Nullable String thumbnailUrl,

        /**
         * 文件大小（字节）
         */
        long fileSize,

        /**
         * MIME类型
         */
        String mimeType,

        /**
         * 文件名
         */
        String fileName,

        /**
         * 时长（秒），仅音频/视频需要（可选）
         */
        @Nullable Integer duration,

        /**
         * 宽度（像素），仅图片/视频需要（可选）
         */
        @Nullable Integer width,

        /**
         * 高度（像素），仅图片/视频需要（可选）
         */
        @Nullable Integer height,

        /**
         * 波形数据（Base64编码），仅音频需要（可选）
         */
        @Nullable String waveformData
) {
    public SendGroupMultimediaRequest {
        // 必填字段校验
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("groupId must be positive");
        }
        if (conversationId <= 0) {
            throw new IllegalArgumentException("conversationId must be positive");
        }
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

        // 可选字段的合理性校验
        if (duration != null && duration < 0) {
            throw new IllegalArgumentException("duration must be non-negative");
        }
        if (width != null && width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
    }

    /**
     * 验证消息类型是否合法
     */
    private static boolean isValidMessageType(String type) {
        return "image".equals(type) || "video".equals(type) ||
                "audio".equals(type) || "file".equals(type);
    }
}