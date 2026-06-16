package com.github.lystran.mochat.multimedia.dto;

/**
 * 媒体文件上传结果
 */
public record MediaUploadResult(
        String mediaId,       // 媒体 ID
        String mediaUrl,      // 访问 URL
        String objectName,    // 对象存储中的 key
        long fileSize,        // 文件大小
        String mimeType,      // MIME 类型
        String fileName       // 原始文件名
) {}