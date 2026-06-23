package com.github.lystran.mochat.multimedia.service;

import com.github.lystran.mochat.multimedia.config.MediaStorageConfig;
import com.github.lystran.mochat.multimedia.config.RustfsConfig;
import com.github.lystran.mochat.multimedia.dto.MediaUploadResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 媒体存储服务集成测试（使用 Docker Compose 启动的 RustFS，无需 Testcontainers）
 *
 * 前置条件：
 * 运行 docker-compose up -d rustfs 启动 RustFS 服务
 *
 * 测试目标：
 * 1. 验证与真实 S3/MinIO 服务的交互
 * 2. 验证文件上传到对象存储
 * 3. 验证文件下载和删除
 * 4. 验证图片、视频、音频的不同处理逻辑
 */
class MediaStorageServiceIntegrationTest {

    private static final String RUSTFS_ENDPOINT = "http://localhost:9002";
    private static final String RUSTFS_ACCESS_KEY = "rustfsadmin";
    private static final String RUSTFS_SECRET_KEY = "rustfsadmin";

    @Mock
    private ThumbnailService thumbnailService;

    @Mock
    private AudioProcessingService audioProcessingService;

    private MediaStorageService mediaStorageService;
    private S3Client s3Client;
    private S3Presigner presigner;
    private String bucketName;

    @BeforeAll
    static void setUpClass() {
        System.out.println("========================================");
        System.out.println("请确保已启动 RustFS 服务：");
        System.out.println("docker-compose up -d rustfs");
        System.out.println("========================================");
    }

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        bucketName = "test-mochat-media-" + UUID.randomUUID().toString().substring(0, 8);

        // 等待 RustFS 服务就绪（增加到 60 秒）
        if (!waitForRustFsReady()) {
            throw new RuntimeException("RustFS service is not available after 60 seconds");
        }

        // 初始化 S3Client 连接到 Docker Compose 启动的 RustFS
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(RUSTFS_ENDPOINT))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(RUSTFS_ENDPOINT))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        // 创建测试桶（带重试）
        boolean bucketCreated = false;
        for (int i = 0; i < 5; i++) {
            try {
                System.out.println("Creating bucket: " + bucketName + " at " + RUSTFS_ENDPOINT + " (attempt " + (i + 1) + ")");
                
                s3Client.createBucket(CreateBucketRequest.builder()
                        .bucket(bucketName)
                        .build());
                System.out.println("✓ Created test bucket: " + bucketName);
                bucketCreated = true;
                break;
                
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException e) {
                System.out.println("⚠ Bucket already exists: " + bucketName);
                bucketCreated = true;
                break;
            } catch (S3Exception e) {
                System.err.println(" Failed to create bucket (attempt " + (i + 1) + "). Error: " + e.getMessage());
                if (i < 4) {
                    Thread.sleep(2000); // 等待 2 秒后重试
                } else {
                    System.err.println("✗ Failed to create bucket after 5 attempts");
                    throw new RuntimeException("Failed to connect to RustFS after retries", e);
                }
            }
        }

        if (!bucketCreated) {
            throw new RuntimeException("Failed to create bucket after all retries");
        }

        // 配置 MediaStorageConfig
        RustfsConfig rustfsConfig = new RustfsConfig(
                RUSTFS_ENDPOINT,
                "us-east-1",
                RUSTFS_ACCESS_KEY,
                RUSTFS_SECRET_KEY,
                bucketName
        );

        MediaStorageConfig config = new MediaStorageConfig(
                MediaStorageConfig.StorageType.rustfs,
                rustfsConfig,
                104857600L,
                new String[]{"image/jpeg", "image/png", "video/mp4", "audio/mpeg"}
        );

        // 创建 MediaStorageService 实例
        mediaStorageService = new MediaStorageService(config, thumbnailService, audioProcessingService);

        // 使用反射替换私有的 s3Client 和 presigner 字段
        setPrivateField(mediaStorageService, "s3Client", s3Client);
        setPrivateField(mediaStorageService, "presigner", presigner);
        setPrivateField(mediaStorageService, "bucketName", bucketName);
    }

    @AfterEach
    void tearDown() {
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
        if (presigner != null) {
            try {
                presigner.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 等待 RustFS 服务就绪
     * @return true 如果服务就绪，false 如果超时
     */
    private boolean waitForRustFsReady() {
        int maxRetries = 60; // 增加到 60 秒
        int retryIntervalMs = 1000;
        
        System.out.println("Waiting for RustFS to be ready...");
        
        for (int i = 0; i < maxRetries; i++) {
            try {
                var tempClient = S3Client.builder()
                        .endpointOverride(URI.create(RUSTFS_ENDPOINT))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY)
                        ))
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build())
                        .build();
                
                tempClient.listBuckets();
                tempClient.close();
                
                System.out.println("✓ RustFS is ready after " + (i + 1) + " seconds");
                return true;
                
            } catch (Exception e) {
                if (i % 10 == 0 && i > 0) {
                    System.out.println("Still waiting for RustFS... (" + i + "s)");
                }
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        
        System.err.println(" RustFS failed to become ready after " + maxRetries + " seconds");
        return false;
    }

    @AfterAll
    static void tearDownClass() {
        System.out.println("========================================");
        System.out.println("测试完成，可以停止 RustFS 服务：");
        System.out.println("docker-compose down rustfs");
        System.out.println("========================================");
    }

    /**
     * 测试普通文件上传到 MinIO
     */
    @Test
    void upload_PdfFile_ShouldSuccessfullyUploadToMinIO() throws Exception {
        byte[] pdfData = "test pdf content".getBytes();
        String filename = "document.pdf";
        String mimeType = "application/pdf";

        MediaUploadResult result = mediaStorageService.upload(pdfData, filename, mimeType);

        assertNotNull(result);
        assertNotNull(result.mediaId());
        assertNotNull(result.mediaUrl());
        assertEquals(filename, result.fileName());
        assertEquals(mimeType, result.mimeType());
        assertEquals(pdfData.length, result.fileSize());
        assertNull(result.thumbnailUrl());
        assertNull(result.waveformData());

        // 验证文件确实存在于 MinIO 中
        String objectName = extractObjectName(result.mediaUrl());
        GetObjectResponse response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build()).response();

        assertNotNull(response);
        assertEquals((long) pdfData.length, response.contentLength());
    }

    /**
     * 测试图片上传并生成缩略图
     */
    @Test
    void upload_ImageFile_ShouldUploadOriginalAndThumbnail() throws Exception {
        byte[] imageData = createTestImage();
        String filename = "photo.jpg";
        String mimeType = "image/jpeg";

        byte[] thumbnailData = createTestThumbnail();
        when(thumbnailService.generateThumbnail(eq(imageData), eq(mimeType))).thenReturn(thumbnailData);

        MediaUploadResult result = mediaStorageService.upload(imageData, filename, mimeType);

        assertNotNull(result);
        assertNotNull(result.mediaUrl());
        assertNotNull(result.thumbnailUrl());
        assertTrue(result.thumbnailUrl().contains("_thumb"));

        // 验证原图和缩略图都存在于 MinIO 中
        String originalObjectName = extractObjectName(result.mediaUrl());
        String thumbnailObjectName = extractObjectName(result.thumbnailUrl());

        assertObjectExists(originalObjectName);
        assertObjectExists(thumbnailObjectName);
    }

    /**
     * 测试视频上传并生成封面
     */
    @Test
    void upload_VideoFile_ShouldUploadOriginalAndCover() throws Exception {
        byte[] videoData = createTestVideo();
        String filename = "video.mp4";
        String mimeType = "video/mp4";

        byte[] thumbnailData = createTestThumbnail();
        when(thumbnailService.generateThumbnail(eq(videoData), eq(mimeType))).thenReturn(thumbnailData);

        MediaUploadResult result = mediaStorageService.upload(videoData, filename, mimeType);

        assertNotNull(result);
        assertNotNull(result.mediaUrl());
        assertNotNull(result.thumbnailUrl());
        assertTrue(result.thumbnailUrl().contains("_thumb"));

        // 验证原视频和封面都存在于 RustFS 中
        String videoObjectName = extractObjectName(result.mediaUrl());
        String coverObjectName = extractObjectName(result.thumbnailUrl());

        assertObjectExists(videoObjectName);
        assertObjectExists(coverObjectName);
    }

    /**
     * 测试音频上传并转码
     */
    @Test
    void upload_AudioFile_ShouldTranscodeAndUpload() throws Exception {
        byte[] wavData = createTestAudio();
        String filename = "voice.wav";
        String mimeType = "audio/wav";

        byte[] mp3Data = createTestMp3();
        String waveformData = "base64_waveform_data";

        when(audioProcessingService.transcodeAudio(eq(wavData), eq(mimeType))).thenReturn(mp3Data);
        when(audioProcessingService.generateWaveformData(eq(mp3Data), eq("audio/mpeg"))).thenReturn(waveformData);

        MediaUploadResult result = mediaStorageService.upload(wavData, filename, mimeType);

        assertNotNull(result);
        assertNotNull(result.mediaUrl());
        assertNull(result.thumbnailUrl());
        assertEquals(waveformData, result.waveformData());
        assertEquals("audio/mpeg", result.mimeType());

        // 验证转码后的 MP3 存在于 MinIO 中
        String audioObjectName = extractObjectName(result.mediaUrl());
        assertObjectExists(audioObjectName);
    }

    /**
     * 测试文件下载
     */
    @Test
    void download_ShouldSuccessfullyDownloadFile() throws Exception {
        byte[] testData = "test download content".getBytes();
        String objectName = "test-files/" + UUID.randomUUID() + ".txt";

        // 先上传文件
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType("text/plain")
                .contentLength((long) testData.length)
                .build(), RequestBody.fromBytes(testData));

        // 使用反射获取 download 方法并调用
        byte[] downloaded = invokePrivateMethod(mediaStorageService, "download", new Class[]{String.class}, objectName);

        assertNotNull(downloaded);
        assertArrayEquals(testData, downloaded);
    }

    /**
     * 测试文件删除
     */
    @Test
    void delete_ShouldSuccessfullyDeleteFile() throws Exception {
        byte[] testData = "test delete content".getBytes();
        String objectName = "test-files/" + UUID.randomUUID() + ".txt";

        // 先上传文件
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType("text/plain")
                .contentLength((long) testData.length)
                .build(), RequestBody.fromBytes(testData));

        // 验证文件存在
        assertObjectExists(objectName);

        // 使用反射调用 delete 方法
        invokePrivateMethod(mediaStorageService, "delete", new Class[]{String.class}, objectName);

        // 验证文件已被删除
        assertThrows(NoSuchKeyException.class, () -> {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectName)
                    .build());
        });
    }

    /**
     * 测试文件大小超限
     */
    @Test
    void upload_FileExceedsMaxSize_ShouldThrowIllegalArgumentException() {
        byte[] largeData = new byte[104857601]; // 超过 100MB
        String filename = "large.jpg";
        String mimeType = "image/jpeg";

        assertThrows(IllegalArgumentException.class, () -> {
            mediaStorageService.upload(largeData, filename, mimeType);
        });
    }

    /**
     * 测试不支持的文件类型
     */
    @Test
    void upload_UnsupportedMimeType_ShouldThrowIllegalArgumentException() {
        byte[] data = new byte[1024];
        String filename = "test.exe";
        String mimeType = "application/octet-stream";

        assertThrows(IllegalArgumentException.class, () -> {
            mediaStorageService.upload(data, filename, mimeType);
        });
    }

    // ========== 辅助方法 ==========

    /**
     * 使用反射设置私有字段
     */
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 使用反射调用私有方法
     */
    private <T> T invokePrivateMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }

    /**
     * 从 URL 中提取 object name
     */
    private String extractObjectName(String mediaUrl) {
        // 例如：/media/download/images/uuid.jpg -> images/uuid.jpg
        return mediaUrl.substring("/media/download/".length());
    }

    /**
     * 断言对象存在于 MinIO 中
     */
    private void assertObjectExists(String objectName) {
        HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build());
        assertNotNull(response);
    }

    private byte[] createTestImage() throws IOException {
        return readResourceFile("test-files/sample-image.jpg");
    }

    private byte[] createTestThumbnail() throws IOException {
        return readResourceFile("test-files/sample-thumbnail.jpg");
    }

    private byte[] createTestVideo() throws IOException {
        return readResourceFile("test-files/sample-video.mp4");
    }

    private byte[] createTestAudio() throws IOException {
        return readResourceFile("test-files/sample-audio.wav");
    }

    private byte[] createTestMp3() throws IOException {
        return readResourceFile("test-files/sample-mp3.mp3");
    }

    /**
     * 从 classpath 读取资源文件
     */
    private byte[] readResourceFile(String path) throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Resource not found: " + path);
            }
            return inputStream.readAllBytes();
        }
    }
}
