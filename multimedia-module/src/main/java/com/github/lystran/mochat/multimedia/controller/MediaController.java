package com.github.lystran.mochat.multimedia.controller;

import com.github.lystran.mochat.multimedia.dto.MediaUploadResult;
import com.github.lystran.mochat.multimedia.service.MediaStorageService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

@Controller("/media")
public final class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MediaStorageService mediaStorageService;

    @Inject
    public MediaController(MediaStorageService mediaStorageService) {
        this.mediaStorageService = mediaStorageService;
    }

    /**
     * 上传媒体文件到 RustFS 对象存储
     * 
     * @param file 上传的文件
     * @return 包含 mediaId、mediaUrl、objectName、fileSize、mimeType、fileName 的响应
     */
    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    public ApiResponse<Map<String, Object>> upload(CompletedFileUpload file) throws IOException {
        if (file == null || file.getSize() == 0) {
            log.warn("Media upload failed: file is empty");
            throw new IllegalArgumentException("File cannot be empty");
        }

        byte[] data = file.getInputStream().readAllBytes();
        String originalFilename = file.getFilename();
        String mimeType = file.getContentType().map(ct -> ct.toString()).orElse("application/octet-stream");

        log.info("Uploading media file: filename={}, size={} bytes, mimeType={}", 
                originalFilename, data.length, mimeType);

        try {
            MediaUploadResult result = mediaStorageService.upload(data, originalFilename, mimeType);

            Map<String, Object> responseData = Map.of(
                    "mediaId", result.mediaId(),
                    "mediaUrl", result.mediaUrl(),
                    "thumbnailUrl", result.thumbnailUrl() != null ? result.thumbnailUrl() : "",
                    "objectName", result.objectName(),
                    "fileSize", result.fileSize(),
                    "mimeType", result.mimeType(),
                    "fileName", result.fileName(),
                    "waveformData", result.waveformData() != null ? result.waveformData() : ""
            );

            log.info("Media upload successful: mediaId={}, mediaUrl={}, thumbnailUrl={}", 
                    result.mediaId(), result.mediaUrl(), result.thumbnailUrl());

            return ApiResponse.ok(responseData);

        } catch (Exception e) {
            log.error("Media upload failed: filename={}, error={}", originalFilename, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 下载媒体文件
     * 
     * @param objectName 对象名称（如：media/uuid.jpg）
     * @return 文件二进制数据
     */
    @Get("/download/{objectName+}")
    public HttpResponse<byte[]> download(@PathVariable String objectName) {
        log.info("Downloading media file: objectName={}", objectName);

        try {
            byte[] data = mediaStorageService.download(objectName);
            String mimeType = inferMimeType(objectName);

            log.info("Media download successful: objectName={}, size={} bytes, mimeType={}", 
                    objectName, data.length, mimeType);

            return HttpResponse.ok(data)
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + objectName + "\"");

        } catch (Exception e) {
            log.error("Media download failed: objectName={}, error={}", objectName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 生成预签名 URL（用于临时访问）
     * 
     * @param objectName 对象名称
     * @param expirySeconds URL 有效期（秒），默认 3600
     * @return 预签名 URL
     */
    @Get("/presigned-url/{objectName+}")
    public ApiResponse<Map<String, Object>> presignedUrl(@PathVariable String objectName,
                                                         @QueryValue(defaultValue = "3600") int expirySeconds) {
        log.info("Generating presigned URL: objectName={}, expirySeconds={}", objectName, expirySeconds);

        try {
            String url = mediaStorageService.generatePresignedUrl(objectName, expirySeconds);
            Map<String, Object> responseData = Map.of("url", url, "expiresIn", expirySeconds);

            log.info("Presigned URL generated successfully: objectName={}, expiresIn={}s", 
                    objectName, expirySeconds);

            return ApiResponse.ok(responseData);

        } catch (Exception e) {
            log.error("Failed to generate presigned URL: objectName={}, error={}", objectName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 删除媒体文件
     * 
     * @param objectName 对象名称
     * @return 删除结果
     */
    @Delete("/{objectName+}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String objectName) {
        log.info("Deleting media file: objectName={}", objectName);

        try {
            mediaStorageService.delete(objectName);
            Map<String, Object> responseData = Map.of("objectName", objectName);

            log.info("Media file deleted successfully: objectName={}", objectName);

            return new ApiResponse<>(true, responseData, "File deleted successfully");

        } catch (Exception e) {
            log.error("Failed to delete media file: objectName={}, error={}", objectName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 根据文件名推断 MIME 类型
     */
    private String inferMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
