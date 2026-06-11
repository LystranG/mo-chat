package com.github.lystran.mochat.logic.service;

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

            // 如果 headBucket 成功，说明桶已存在，无需创建

        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            // 桶不存在，创建它
            try {
                s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
                );
            } catch (Exception createException) {
                throw new RuntimeException("Failed to create RustFS bucket: " + bucketName, createException);
            }
        } catch (Exception e) {
            // 其他错误（如连接失败），记录警告但不阻止启动
            System.err.println("Warning: Failed to check/create RustFS bucket: " + e.getMessage());
        }
    }

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
                log.info("Processing image file, generating thumbnail");
                try {
                    byte[] thumbnailData = thumbnailService.generateThumbnail(data, mimeType);
                    String thumbnailObjectName = generateThumbnailObjectName(objectName);

                    s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(thumbnailObjectName)
                            .contentType(mimeType)
                            .contentLength((long) thumbnailData.length)
                            .build(), RequestBody.fromBytes(thumbnailData)
                    );

                    thumbnailUrl = buildMediaUrl(thumbnailObjectName);
                    log.info("Thumbnail generated and uploaded, thumbnailUrl={}", thumbnailUrl);
                } catch (IOException e) {
                    log.error("Failed to generate thumbnail", e);
                    throw new RuntimeException("Failed to generate thumbnail", e);
                }
            } else if (isAudioType(mimeType)) {
                log.info("Processing audio file, transcoding and generating waveform");
                try {
                    byte[] transcodedData = audioProcessingService.transcodeAudio(data, mimeType);
                    if (transcodedData.length != data.length) {
                        processedData = transcodedData;
                        s3Client.putObject(PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(objectName)
                                .contentType("audio/mpeg")
                                .contentLength((long) processedData.length)
                                .build(), RequestBody.fromBytes(processedData)
                        );
                        log.info("Audio transcoded and uploaded, newSize={} bytes", processedData.length);
                    }
                    
                    waveformData = audioProcessingService.generateWaveformData(processedData, "audio/mpeg");
                    log.info("Audio waveform generated, waveformDataSize={} bytes", waveformData.length());
                } catch (IOException e) {
                    log.error("Failed to process audio", e);
                    throw new RuntimeException("Failed to process audio", e);
                }
            } else {
                log.info("Uploading file without special processing");
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectName)
                        .contentType(mimeType)
                        .contentLength((long) data.length)
                        .build(), RequestBody.fromBytes(data)
                );
            }

            MediaUploadResult result = new MediaUploadResult(
                    UUID.randomUUID().toString(),
                    mediaUrl,
                    thumbnailUrl,
                    objectName,
                    processedData.length,
                    isAudioType(mimeType) ? "audio/mpeg" : mimeType,
                    originalFilename,
                    waveformData
            );
            
            log.info("Media upload completed successfully, mediaId={}, objectName={}", 
                    result.mediaId(), objectName);
            
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
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }

    private void validateFile(long fileSize, String mimeType) {
        if (fileSize > maxFileSize) {
            throw new MediaUploadException(
                "File size exceeds limit: " + fileSize + " > " + maxFileSize
            );
        }

        if (!isAllowedType(mimeType)) {
            throw new MediaUploadException("Unsupported media type: " + mimeType);
        }
    }

    private boolean isAllowedType(String mimeType) {
        if (mimeType == null) return false;

        return mimeType.startsWith("image/") ||
               mimeType.startsWith("audio/") ||
               mimeType.startsWith("video/") ||
               mimeType.equals("application/pdf") ||
               mimeType.equals("application/zip") ||
               mimeType.equals("application/msword") ||
               mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private String generateObjectName(String originalFilename) {
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalFilename.substring(dotIndex);
        }
        return "media/" + UUID.randomUUID().toString() + extension;
    }

    private String buildMediaUrl(String objectName) {
        return "/media/download/" + objectName;
    }

    private boolean isImageType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    private boolean isAudioType(String mimeType) {
        return mimeType != null && mimeType.startsWith("audio/");
    }

    private String generateThumbnailObjectName(String originalObjectName) {
        int lastDotIndex = originalObjectName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String baseName = originalObjectName.substring(0, lastDotIndex);
            String extension = originalObjectName.substring(lastDotIndex);
            return baseName + "_thumb" + extension;
        }
        return originalObjectName + "_thumb";
    }

    public record MediaUploadResult(
            String mediaId,
            String mediaUrl,
            String thumbnailUrl,
            String objectName,
            long fileSize,
            String mimeType,
            String fileName,
            String waveformData
    ) {}
}