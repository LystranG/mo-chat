package com.github.lystran.mochat.multimedia.dto;

import io.micronaut.core.annotation.Nullable;

/**
 * 私聊多媒体消息发送请求 DTO
 *
 * 用于向指定用户发送多媒体消息（图片、音频、视频、文件等）。
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * {
 *   "sessionId": "session-abc-123",
 *   "toUid": 1002,
 *   "messageType": "image",
 *   "mediaUrl": "/media/download/media/uuid.jpg",
 *   "thumbnailUrl": "/media/download/media/uuid_thumb.jpg",
 *   "fileSize": 102400,
 *   "mimeType": "image/jpeg",
 *   "fileName": "photo.jpg",
 *   "duration": null,
 *   "width": 1920,
 *   "height": 1080,
 *   "waveformData": null
 * }
 * }</pre>
 */
public record SendPrivateMultimediaRequest(
        /**
         * 会话ID（用于身份验证）
         */
        String sessionId,

        /**
         * 接收者用户ID
         */
        long toUid,

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
    public SendPrivateMultimediaRequest {
        // 必填字段校验
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
        if (toUid <= 0) {
            throw new IllegalArgumentException("toUid must be positive");
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