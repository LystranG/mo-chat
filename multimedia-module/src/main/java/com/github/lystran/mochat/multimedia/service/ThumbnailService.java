package com.github.lystran.mochat.multimedia.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ws.schild.jave.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 缩略图生成服务（基于 FFmpeg）
 * 
 * 支持从图片和视频中提取缩略图：
 * - 图片：直接缩放生成缩略图
 * - 视频：从指定时间点提取帧作为缩略图
 */
@Singleton
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    private static final int THUMBNAIL_WIDTH = 200;
    private static final int THUMBNAIL_HEIGHT = 200;
    private static final double QUALITY = 0.8;
    
    // 视频缩略图提取时间点（秒）
    private static final int VIDEO_THUMBNAIL_TIME = 5;
    
    // FFmpeg 进程超时时间（秒）
    private static final int FFMPEG_TIMEOUT_SECONDS = 30;

    /**
     * 生成缩略图
     * 
     * @param originalData 原始文件数据（图片或视频）
     * @param mimeType MIME 类型
     * @return 缩略图数据（JPEG 格式）
     */
    public byte[] generateThumbnail(byte[] originalData, String mimeType) throws IOException {
        if (isImageType(mimeType)) {
            return generateImageThumbnail(originalData, mimeType);
        } else if (isVideoType(mimeType)) {
            return generateVideoThumbnail(originalData, mimeType);
        } else {
            throw new IllegalArgumentException("Unsupported type for thumbnail generation: " + mimeType);
        }
    }

    /**
     * 从图片生成缩略图（使用 FFmpeg）
     */
    private byte[] generateImageThumbnail(byte[] imageData, String mimeType) throws IOException {
        log.info("Generating image thumbnail with FFmpeg, mimeType={}, size={} bytes", 
                mimeType, imageData.length);

        File inputFile = null;
        File outputFile = null;

        try {
            // 创建临时输入文件
            String inputExtension = getExtensionFromImageMimeType(mimeType);
            inputFile = File.createTempFile("image_input_", "." + inputExtension);
            Files.write(inputFile.toPath(), imageData);

            // 创建临时输出文件
            outputFile = File.createTempFile("thumbnail_output_", ".jpg");

            // 使用 FFmpeg 缩放图片
            // 命令：ffmpeg -i input.jpg -vf "scale=200:200:force_original_aspect_ratio=decrease,pad=200:200:(ow-iw)/2:(oh-ih)/2" output.jpg
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputFile.getAbsolutePath(),
                "-vf", String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", 
                        THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
                "-q:v", String.valueOf((int) ((1 - QUALITY) * 10)),  // JPEG 质量 (1-10, 1最好)
                outputFile.getAbsolutePath()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            boolean completed = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.error("FFmpeg image thumbnail generation timeout after {} seconds", FFMPEG_TIMEOUT_SECONDS);
                throw new RuntimeException("FFmpeg process timeout while generating image thumbnail");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                log.error("FFmpeg image thumbnail generation failed: {}", error);
                throw new RuntimeException("FFmpeg failed to generate image thumbnail");
            }

            byte[] result = Files.readAllBytes(outputFile.toPath());
            log.info("Image thumbnail generated successfully, size={} bytes", result.length);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg process interrupted", e);
        } finally {
            // 清理临时文件
            if (inputFile != null && inputFile.exists()) {
                try {
                    Files.deleteIfExists(inputFile.toPath());
                } catch (IOException e) {
                    log.warn("Failed to delete temp input file: {}", inputFile.getAbsolutePath(), e);
                }
            }
            if (outputFile != null && outputFile.exists()) {
                try {
                    Files.deleteIfExists(outputFile.toPath());
                } catch (IOException e) {
                    log.warn("Failed to delete temp output file: {}", outputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 从视频提取缩略图（使用 FFmpeg）
     */
    private byte[] generateVideoThumbnail(byte[] videoData, String mimeType) throws IOException {
        log.info("Generating video thumbnail with FFmpeg, mimeType={}, size={} bytes", 
                mimeType, videoData.length);

        File inputFile = null;
        File outputFile = null;

        try {
            // 创建临时输入文件
            String inputExtension = getExtensionFromVideoMimeType(mimeType);
            inputFile = File.createTempFile("video_input_", "." + inputExtension);
            Files.write(inputFile.toPath(), videoData);

            // 创建临时输出文件
            outputFile = File.createTempFile("video_thumbnail_", ".jpg");

            // 使用 FFmpeg 从视频提取帧
            // 命令：ffmpeg -ss 5 -i input.mp4 -vframes 1 -vf "scale=200:200:force_original_aspect_ratio=decrease,pad=200:200:(ow-iw)/2:(oh-ih)/2" output.jpg
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-ss", String.valueOf(VIDEO_THUMBNAIL_TIME),  // 从第5秒开始
                "-i", inputFile.getAbsolutePath(),
                "-vframes", "1",  // 只提取1帧
                "-vf", String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", 
                        THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
                "-q:v", "2",  // JPEG 质量
                outputFile.getAbsolutePath()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            boolean completed = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.error("FFmpeg video thumbnail extraction timeout after {} seconds", FFMPEG_TIMEOUT_SECONDS);
                throw new RuntimeException("FFmpeg process timeout while extracting video thumbnail");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                log.error("FFmpeg video thumbnail extraction failed: {}", error);
                throw new RuntimeException("FFmpeg failed to extract video thumbnail");
            }

            byte[] result = Files.readAllBytes(outputFile.toPath());
            log.info("Video thumbnail extracted successfully, size={} bytes", result.length);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg process interrupted", e);
        } finally {
            // 清理临时文件
            if (inputFile != null && inputFile.exists()) {
                try {
                    Files.deleteIfExists(inputFile.toPath());
                } catch (IOException e) {
                    log.warn("Failed to delete temp input file: {}", inputFile.getAbsolutePath(), e);
                }
            }
            if (outputFile != null && outputFile.exists()) {
                try {
                    Files.deleteIfExists(outputFile.toPath());
                } catch (IOException e) {
                    log.warn("Failed to delete temp output file: {}", outputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 判断是否为图片类型
     */
    private boolean isImageType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /**
     * 判断是否为视频类型
     */
    private boolean isVideoType(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    /**
     * 根据图片 MIME 类型获取扩展名
     */
    private String getExtensionFromImageMimeType(String mimeType) {
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) return "jpg";
        if (mimeType.contains("png")) return "png";
        if (mimeType.contains("gif")) return "gif";
        if (mimeType.contains("webp")) return "webp";
        return "jpg";
    }

    /**
     * 根据视频 MIME 类型获取扩展名
     */
    private String getExtensionFromVideoMimeType(String mimeType) {
        if (mimeType.contains("mp4")) return "mp4";
        if (mimeType.contains("avi")) return "avi";
        if (mimeType.contains("mov")) return "mov";
        if (mimeType.contains("webm")) return "webm";
        if (mimeType.contains("flv")) return "flv";
        return "mp4";
    }
}
