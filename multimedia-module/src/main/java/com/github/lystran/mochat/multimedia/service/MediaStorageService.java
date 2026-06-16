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
                    objectName,
                    processedData.length,
                    isAudioType(mimeType) ? "audio/mpeg" : mimeType,
                    originalFilename
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

    private boolean isAudioType(String mimeType) {
        return mimeType != null && mimeType.startsWith("audio/");
    }
}