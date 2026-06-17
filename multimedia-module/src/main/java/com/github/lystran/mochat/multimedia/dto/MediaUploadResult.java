package com.github.lystran.mochat.multimedia.dto;

/**
 * 媒体文件上传结果
 */
public record MediaUploadResult(
        String mediaId,       // 媒体 ID
        String mediaUrl,      // 访问 URL（原图/原视频/原音频）
        String thumbnailUrl,  // 缩略图 URL（仅图片/视频有值，音频为 null）
        String objectName,    // 对象存储中的 key（原图/原视频/原音频）
        long fileSize,        // 文件大小（原图/原视频/原音频）
        String mimeType,      // MIME 类型
        String fileName,      // 原始文件名
        String waveformData   // 波形数据（仅音频有值，其他为 null）
) {}