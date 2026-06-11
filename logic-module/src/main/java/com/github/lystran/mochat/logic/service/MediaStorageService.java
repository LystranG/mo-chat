package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
    
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;
    private final long maxFileSize;
    private final ThumbnailService thumbnailService;

    public MediaStorageService(MediaStorageConfig config, ThumbnailService thumbnailService) {
        Objects.requireNonNull(config, "config");
        this.thumbnailService = Objects.requireNonNull(thumbnailService, "thumbnailService");

        RustfsConfig rustfsConfig = config.rustfs();
        String endpoint = rustfsConfig.endpoint();

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
        
        String objectName = generateObjectName(originalFilename);
        
        try {
            s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType(mimeType)
                .contentLength((long) data.length)
                .build(), RequestBody.fromBytes(data)
            );
            
            String mediaUrl = buildMediaUrl(objectName);
            String thumbnailUrl = null;
            
            if (isImageType(mimeType)) {
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
                } catch (IOException e) {
                    throw new RuntimeException("Failed to generate thumbnail", e);
                }
            }
            
            return new MediaUploadResult(
                UUID.randomUUID().toString(),
                mediaUrl,
                thumbnailUrl,
                objectName,
                data.length,
                mimeType,
                originalFilename
            );
            
        } catch (Exception e) {
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
        String fileName
    ) {}
}