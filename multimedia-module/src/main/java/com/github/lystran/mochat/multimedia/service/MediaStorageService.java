package com.github.lystran.mochat.multimedia.service;

import com.github.lystran.mochat.multimedia.config.MediaStorageConfig;
import com.github.lystran.mochat.multimedia.config.RustfsConfig;
import com.github.lystran.mochat.multimedia.dto.MediaUploadResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Singleton
public class MediaStorageService {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;
    private final long maxFileSize;
    private final ThumbnailService thumbnailService;
    private final AudioProcessingService audioProcessingService;

    public MediaStorageService(MediaStorageConfig config, ThumbnailService thumbnailService, AudioProcessingService audioProcessingService) {
        Objects.requireNonNull(config, "config");
        this.thumbnailService = Objects.requireNonNull(thumbnailService, "thumbnailService");
        this.audioProcessingService = Objects.requireNonNull(audioProcessingService, "audioProcessingService");

        RustfsConfig rustfsConfig = config.rustfs();
        String endpoint = rustfsConfig.endpoint();

        log.info("Initializing MediaStorageService with RustFS endpoint={}", endpoint);

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(rustfsConfig.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfsConfig.accessKey(), rustfsConfig.secretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(rustfsConfig.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfsConfig.accessKey(), rustfsConfig.secretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        this.bucketName = rustfsConfig.bucket();
        this.maxFileSize = config.maxFileSize();

        initializeBucket();
        log.info("MediaStorageService initialized successfully, bucket={}", bucketName);
    }

    private void initializeBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
            );

        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder()
                        .bucket(bucketName)
                        .build()
                );
            } catch (Exception createException) {
                throw new RuntimeException("Failed to create RustFS bucket: " + bucketName, createException);
            }
        } catch (Exception e) {
            log.warn("Failed to check/create RustFS bucket: {}", e.getMessage(), e);
        }
    }

    /**
     * 上传媒体文件到 RustFS 对象存储
     * 
     * <p>业务规则：</p>
     * <ul>
     *   <li><b>图片</b>：原图和缩略图都上传，返回两个 URL</li>
     *   <li><b>视频</b>：原视频和封面缩略图都上传，返回两个 URL</li>
     *   <li><b>音频</b>：转码为 MP3 并生成波形数据，只返回一个 URL</li>
     *   <li><b>其他文件</b>：直接上传，只返回一个 URL</li>
     * </ul>
     * 
     * @param data 文件二进制数据
     * @param originalFilename 原始文件名
     * @param mimeType MIME 类型
     * @return 上传结果，包含 mediaUrl、thumbnailUrl（如果有）、waveformData（如果有）
     */
    public MediaUploadResult upload(byte[] data, String originalFilename, String mimeType) {
        validateFile(data.length, mimeType);

        log.info("Uploading media file, filename={}, mimeType={}, size={} bytes",
                originalFilename, mimeType, data.length);

        String objectName = generateObjectName(originalFilename);

        try {
            byte[] processedData = data;
            String mediaUrl = buildMediaUrl(objectName);
            String thumbnailUrl = null;
            String waveformData = null;

            if (isImageType(mimeType)) {
                // ========== 图片处理：原图 + 缩略图都上传 ==========
                log.info("Processing image file, uploading original and generating thumbnail");
                
                // 1. 先上传原图
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectName)
                        .contentType(mimeType)
                        .contentLength((long) data.length)
                        .build(), RequestBody.fromBytes(data)
                );
                log.info("Original image uploaded, objectName={}", objectName);
                
                // 2. 生成并上传缩略图
                try {
                    byte[] thumbnailData = thumbnailService.generateThumbnail(data, mimeType);
                    String thumbnailObjectName = generateThumbnailObjectName(objectName);

                    s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(thumbnailObjectName)
                            .contentType("image/jpeg")  // 缩略图统一为 JPEG
                            .contentLength((long) thumbnailData.length)
                            .build(), RequestBody.fromBytes(thumbnailData)
                    );

                    thumbnailUrl = buildMediaUrl(thumbnailObjectName);
                    log.info("Thumbnail generated and uploaded, thumbnailUrl={}", thumbnailUrl);
                    
                } catch (IOException e) {
                    log.error("Failed to generate thumbnail", e);
                    // 缩略图生成失败不影响原图上传，继续返回
                }
                
            } else if (isVideoType(mimeType)) {
                // ========== 视频处理：原视频 + 封面缩略图都上传 ==========
                log.info("Processing video file, uploading original and generating cover thumbnail");
                
                // 1. 先上传原视频
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectName)
                        .contentType(mimeType)
                        .contentLength((long) data.length)
                        .build(), RequestBody.fromBytes(data)
                );
                log.info("Original video uploaded, objectName={}", objectName);
                
                // 2. 生成并上传封面缩略图（从第5秒提取帧）
                try {
                    byte[] thumbnailData = thumbnailService.generateThumbnail(data, mimeType);
                    String thumbnailObjectName = generateThumbnailObjectName(objectName);

                    s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(thumbnailObjectName)
                            .contentType("image/jpeg")  // 封面统一为 JPEG
                            .contentLength((long) thumbnailData.length)
                            .build(), RequestBody.fromBytes(thumbnailData)
                    );

                    thumbnailUrl = buildMediaUrl(thumbnailObjectName);
                    log.info("Video cover thumbnail generated and uploaded, thumbnailUrl={}", thumbnailUrl);
                    
                } catch (IOException e) {
                    log.error("Failed to generate video cover thumbnail", e);
                    // 封面生成失败不影响原视频上传，继续返回
                }
                
            } else if (isAudioType(mimeType)) {
                // ========== 音频处理：转码为 MP3 + 生成波形数据 ==========
                log.info("Processing audio file, transcoding to MP3 and generating waveform");
                
                try {
                    // 如果已经是 MP3，直接上传原数据
                    if (mimeType.equals("audio/mpeg")) {
                        s3Client.putObject(PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(objectName)
                                .contentType(mimeType)
                                .contentLength((long) data.length)
                                .build(), RequestBody.fromBytes(data)
                        );
                        log.info("Audio uploaded without transcoding (already MP3)");
                        
                        // 生成波形数据
                        waveformData = audioProcessingService.generateWaveformData(data, mimeType);
                        log.info("Audio waveform generated, waveformDataSize={} bytes", waveformData.length());
                        
                    } else {
                        // 需要转码为 MP3
                        byte[] transcodedData = audioProcessingService.transcodeAudio(data, mimeType);
                        processedData = transcodedData;
                        
                        // 上传转码后的 MP3
                        s3Client.putObject(PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(objectName)
                                .contentType("audio/mpeg")
                                .contentLength((long) processedData.length)
                                .build(), RequestBody.fromBytes(processedData)
                        );
                        log.info("Audio transcoded and uploaded, originalSize={} bytes, mp3Size={} bytes", 
                                data.length, processedData.length);

                        // 生成波形数据
                        waveformData = audioProcessingService.generateWaveformData(processedData, "audio/mpeg");
                        log.info("Audio waveform generated, waveformDataSize={} bytes", waveformData.length());
                    }
                    
                } catch (IOException e) {
                    log.error("Failed to process audio", e);
                    throw new RuntimeException("Failed to process audio", e);
                }
                
            } else {
                // ========== 其他文件：直接上传 ==========
                log.info("Uploading file without special processing");
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectName)
                        .contentType(mimeType)
                        .contentLength((long) data.length)
                        .build(), RequestBody.fromBytes(data)
                );
            }

            // 构建返回结果
            MediaUploadResult result = new MediaUploadResult(
                    UUID.randomUUID().toString(),
                    mediaUrl,                                    // 原图/原视频/原音频 URL
                    thumbnailUrl,                                // 缩略图 URL（图片/视频有值，其他为 null）
                    objectName,                                  // 原图/原视频/原音频的 objectName
                    processedData.length,                        // 原图/原视频/转码后音频的文件大小
                    isAudioType(mimeType) ? "audio/mpeg" : mimeType,  // 音频统一为 audio/mpeg
                    originalFilename,
                    waveformData                                 // 波形数据（仅音频有值，其他为 null）
            );

            log.info("Media upload completed successfully, mediaId={}, objectName={}, hasThumbnail={}",
                    result.mediaId(), objectName, thumbnailUrl != null);

            return result;

        } catch (Exception e) {
            log.error("Failed to upload media to RustFS, filename={}", originalFilename, e);
            throw new RuntimeException("Failed to upload media to RustFS", e);
        }
    }

    public byte[] download(String objectName) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build()
            ).asByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download media from RustFS: " + objectName, e);
        }
    }

    public void delete(String objectName) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete media from RustFS: " + objectName, e);
        }
    }

    public String generatePresignedUrl(String objectName, int expirySeconds) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirySeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL for: " + objectName, e);
        }
    }

    private void validateFile(long fileSize, String mimeType) {
        if (fileSize > maxFileSize) {
            throw new IllegalArgumentException(
                    String.format("File size %d exceeds maximum allowed size %d", fileSize, maxFileSize)
            );
        }

        if (!isSupportedMimeType(mimeType)) {
            throw new IllegalArgumentException("Unsupported MIME type: " + mimeType);
        }
    }

    private String generateObjectName(String originalFilename) {
        String extension = getFileExtension(originalFilename);
        return String.format("%s/%s.%s",
                getMediaTypeFolder(originalFilename),
                UUID.randomUUID().toString(),
                extension
        );
    }

    private String generateThumbnailObjectName(String originalObjectName) {
        // 例如：images/uuid.jpg -> images/uuid_thumb.jpg
        return originalObjectName.replaceFirst("\\.", "_thumb.");
    }

    private String buildMediaUrl(String objectName) {
        return String.format("/media/download/%s", objectName);
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex + 1).toLowerCase() : "";
    }

    private String getMediaTypeFolder(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp")) {
            return "images";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") ||
                lower.endsWith(".mov") || lower.endsWith(".webm")) {
            return "videos";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg") || lower.endsWith(".flac")) {
            return "audios";
        }
        return "files";
    }

    private boolean isSupportedMimeType(String mimeType) {
        if (mimeType == null) return false;

        return mimeType.startsWith("image/") ||
                mimeType.startsWith("audio/") ||
                mimeType.startsWith("video/") ||
                mimeType.equals("application/pdf") ||
                mimeType.equals("application/zip") ||
                mimeType.equals("application/msword") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private boolean isImageType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    private boolean isVideoType(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    private boolean isAudioType(String mimeType) {
        return mimeType != null && mimeType.startsWith("audio/");
    }
}