package com.github.lystran.mochat.multimedia.dto;

import io.micronaut.core.annotation.Nullable;

/**
 * 多媒体元数据，对应开题报告要求：
 * - URL、格式、大小、时长
 * - 缩略图 URL（图片）
 * - 波形数据（音频）
 */
public record MultimediaMetadata(
        String type,              // image/video/audio/file
        String mediaUrl,          // 媒体文件 URL
        @Nullable String thumbnailUrl,  // 缩略图 URL（仅图片/视频）
        long fileSize,            // 文件大小（字节）
        String mimeType,          // MIME 类型
        String fileName,          // 文件名
        @Nullable Integer duration,     // 时长（秒，仅音视频）
        @Nullable Integer width,        // 宽度（像素，仅图片/视频）
        @Nullable Integer height,       // 高度（像素，仅图片/视频）
        @Nullable String waveformData // 波形数据（Base64，仅音频）
) {}