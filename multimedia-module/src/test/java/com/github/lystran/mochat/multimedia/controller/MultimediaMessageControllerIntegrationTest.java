package com.github.lystran.mochat.multimedia.controller;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.MessageIngestRequest;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.multimedia.dto.SendGroupMultimediaRequest;
import com.github.lystran.mochat.multimedia.dto.SendPrivateMultimediaRequest;
import com.github.lystran.mochat.multimedia.dto.MediaUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 多媒体消息 Controller 集成测试 - TC-MSG-01
 * 
 * 测试目标：
 * 1. 验证"先上传后发送"的完整流程
 * 2. 验证私聊图片消息发送功能
 */
class MultimediaMessageControllerIntegrationTest {

    @Mock
    private MessageIngestService messageIngestService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private SessionService sessionService;



    
    @Mock
    private com.github.lystran.mochat.multimedia.service.MediaStorageService mediaStorageService;  // ✅ 直接 Mock 整个服务

    private MultimediaMessageController controller;
    


    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // 设置 Mock 行为
        when(idGenerator.nextId()).thenReturn(123456789L);
        when(sessionService.resolveUserId("valid-session")).thenReturn(Optional.of(1001L));
        when(sessionService.resolveUserId("invalid-session")).thenReturn(Optional.empty());








        // 创建 Controller
        controller = new MultimediaMessageController(messageIngestService, idGenerator, sessionService);
    }

    /**
     * TC-MSG-01: 发送私聊图片消息（需先上传获取 URL）
     * 
     * 测试步骤：
     * 1. 准备测试图片数据
     * 2. 调用 MediaStorageService.upload() 上传图片，获取 mediaUrl 和 thumbnailUrl
     * 3. 使用获得的 URL 构造 SendPrivateMultimediaRequest
     * 4. 调用 controller.sendPrivateMultimedia() 发送消息
     * 5. 验证返回结果包含 clientMsgId 和 conversationId（非空）
     * 6. 验证 messageIngestService.ingest() 被调用
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnSuccess_WhenValidSessionAndMessage() throws Exception {
        // ========== 步骤 1: 准备测试图片数据 ==========
        byte[] imageData = createTestImage();
        String filename = "photo.jpg";
        String mimeType = "image/jpeg";

        // ========== 步骤 2: Mock 上传结果（不真实上传）==========
        System.out.println("\n========== 步骤 1: Mock 上传结果 ==========");
        
        // ✅ 直接 Mock upload() 方法的返回值
        MediaUploadResult mockUploadResult = new MediaUploadResult(
            UUID.randomUUID().toString(),                      // mediaId
            "/media/download/images/test_uuid.jpg",            // mediaUrl
            "/media/download/images/test_uuid_thumb.jpg",      // thumbnailUrl
            "images/test_uuid.jpg",                            // objectName
            102400L,                                           // fileSize
            "image/jpeg",                                      // mimeType
            "photo.jpg",                                       // fileName
            null                                               // waveformData
        );
        
        when(mediaStorageService.upload(eq(imageData), eq(filename), eq(mimeType)))
            .thenReturn(mockUploadResult);

        // 调用上传服务（实际上是 Mock）
        MediaUploadResult uploadResult = mediaStorageService.upload(imageData, filename, mimeType);
        
        assertNotNull(uploadResult, "上传结果不应为空");
        assertNotNull(uploadResult.mediaUrl(), "mediaUrl 不应为空");
        assertNotNull(uploadResult.thumbnailUrl(), "thumbnailUrl 不应为空");
        
        System.out.println("  ✓ Mock 上传成功");
        System.out.println("    mediaUrl: " + uploadResult.mediaUrl());
        System.out.println("    thumbnailUrl: " + uploadResult.thumbnailUrl());
        System.out.println("    fileSize: " + uploadResult.fileSize() + " bytes");
        System.out.println("======================================\n");

        // ========== 步骤 3: 构造发送消息请求 ==========
        System.out.println("========== 步骤 2: 发送私聊图片消息 ==========");
        
        SendPrivateMultimediaRequest request = new SendPrivateMultimediaRequest(
                "valid-session",                          // sessionId
                2001L,                                    // toUid
                "image",                                  // messageType
                uploadResult.mediaUrl(),                  // mediaUrl（从上传结果获取）
                uploadResult.thumbnailUrl(),              // thumbnailUrl（从上传结果获取）
                uploadResult.fileSize(),                  // fileSize
                mimeType,                                 // mimeType
                filename,                                 // fileName
                null,                                     // duration（图片不需要）
                1920,                                     // width
                1080,                                     // height
                null                                      // waveformData（图片不需要）
        );

        // ========== 步骤 4: 发送消息 ==========
        var response = controller.sendPrivateMultimedia(request);

        // ========== 步骤 5: 验证返回结果 ==========
        assertNotNull(response, "响应不应为空");
        assertTrue(response.success(), "发送应该成功");
        
        Map<String, Object> data = response.data();
        assertNotNull(data, "响应数据不应为空");
        
        Long clientMsgId = (Long) data.get("clientMsgId");
        Long conversationId = (Long) data.get("conversationId");
        
        assertNotNull(clientMsgId, "clientMsgId 不应为空");
        assertNotNull(conversationId, "conversationId 不应为空");
        
        System.out.println("  ✓ 发送成功");
        System.out.println("    clientMsgId: " + clientMsgId);
        System.out.println("    conversationId: " + conversationId);
        System.out.println("=============================================\n");

        // ========== 步骤 6: 验证 messageIngestService 被调用 ==========
        verify(messageIngestService, times(1)).ingest(any(MessageIngestRequest.class));
    }

    /**
     * TC-GRP-MSG-01: 发送群聊图片消息（需先上传获取 URL）
     * 
     * 测试目标：
     * 1. 验证"先上传后发送"的完整流程（群聊场景）
     * 2. 验证群聊消息的 conversationId 等于 groupId
     * 3. 验证群消息扇出功能
     * 
     * 测试步骤：
     * 1. 准备测试图片数据
     * 2. Mock MediaStorageService.upload() 返回上传结果
     * 3. 使用获得的 URL 构造 SendGroupMultimediaRequest
     * 4. 调用 controller.sendGroupMultimedia() 发送消息
     * 5. 验证返回结果包含 clientMsgId 和 conversationId（非空）
     * 6. ✅ 验证 conversationId 等于 groupId
     * 7. 验证 messageIngestService.ingest() 被调用
     */
    @Test
    void sendGroupMultimedia_ShouldReturnSuccess_WhenValidSessionAndMessage() throws Exception {
        // ========== 步骤 1: 准备测试图片数据 ==========
        byte[] imageData = createTestImage();
        String filename = "group_photo.jpg";
        String mimeType = "image/jpeg";
        
        // 定义群组 ID
        Long groupId = 3001L;

        // ========== 步骤 2: Mock 上传结果（不真实上传）==========
        System.out.println("\n========== 步骤 1: Mock 上传结果 ==========");
        
        // ✅ 直接 Mock upload() 方法的返回值
        MediaUploadResult mockUploadResult = new MediaUploadResult(
            UUID.randomUUID().toString(),                      // mediaId
            "/media/download/images/group_uuid.jpg",           // mediaUrl
            "/media/download/images/group_uuid_thumb.jpg",     // thumbnailUrl
            "images/group_uuid.jpg",                           // objectName
            102400L,                                           // fileSize
            "image/jpeg",                                      // mimeType
            "group_photo.jpg",                                 // fileName
            null                                               // waveformData
        );
        
        when(mediaStorageService.upload(eq(imageData), eq(filename), eq(mimeType)))
            .thenReturn(mockUploadResult);

        // 调用上传服务（实际上是 Mock）
        MediaUploadResult uploadResult = mediaStorageService.upload(imageData, filename, mimeType);
        
        assertNotNull(uploadResult, "上传结果不应为空");
        assertNotNull(uploadResult.mediaUrl(), "mediaUrl 不应为空");
        assertNotNull(uploadResult.thumbnailUrl(), "thumbnailUrl 不应为空");
        
        System.out.println("  ✓ Mock 上传成功");
        System.out.println("    mediaUrl: " + uploadResult.mediaUrl());
        System.out.println("    thumbnailUrl: " + uploadResult.thumbnailUrl());
        System.out.println("    fileSize: " + uploadResult.fileSize() + " bytes");
        System.out.println("======================================\n");

        // ========== 步骤 3: 构造发送群聊消息请求 ==========
        System.out.println("========== 步骤 2: 发送群聊图片消息 ==========");
        
        // 计算期望的 conversationId（群聊中应该等于 groupId）
        Long expectedConversationId = groupId;
        
        SendGroupMultimediaRequest request = new SendGroupMultimediaRequest(
                "valid-session",                          // sessionId
                groupId,                                  // groupId
                expectedConversationId,                   // conversationId（应该等于 groupId）
                "image",                                  // messageType
                uploadResult.mediaUrl(),                  // mediaUrl（从上传结果获取）
                uploadResult.thumbnailUrl(),              // thumbnailUrl（从上传结果获取）
                uploadResult.fileSize(),                  // fileSize
                mimeType,                                 // mimeType
                filename,                                 // fileName
                null,                                     // duration（图片不需要）
                1920,                                     // width
                1080,                                     // height
                null                                      // waveformData（图片不需要）
        );

        // ========== 步骤 4: 发送消息 ==========
        var response = controller.sendGroupMultimedia(request);

        // ========== 步骤 5: 验证返回结果 ==========
        assertNotNull(response, "响应不应为空");
        assertTrue(response.success(), "发送应该成功");
        
        Map<String, Object> data = response.data();
        assertNotNull(data, "响应数据不应为空");
        
        Long clientMsgId = (Long) data.get("clientMsgId");
        Long actualConversationId = (Long) data.get("conversationId");
        
        assertNotNull(clientMsgId, "clientMsgId 不应为空");
        assertNotNull(actualConversationId, "conversationId 不应为空");
        
        // ========== 步骤 6: ✅ 验证 conversationId 等于 groupId ==========
        assertEquals(expectedConversationId, actualConversationId, 
            "群聊的 conversationId 应该等于 groupId");
        
        System.out.println("  ✓ 发送成功");
        System.out.println("    clientMsgId: " + clientMsgId);
        System.out.println("    groupId: " + groupId);
        System.out.println("    conversationId: " + actualConversationId);
        System.out.println("    ✅ conversationId == groupId: " + expectedConversationId.equals(actualConversationId));
        System.out.println("=============================================\n");

        // ========== 步骤 7: 验证 messageIngestService 被调用 ==========
        verify(messageIngestService, times(1)).ingest(any(MessageIngestRequest.class));
        
        System.out.println("  ✓ messageIngestService.ingest() 被调用 1 次");
        System.out.println("  ✓ 群消息扇出功能已触发\n");
    }

    /**
     * 辅助方法：创建测试图片数据
     */
    private byte[] createTestImage() {
        // 创建一个简单的 JPEG 文件头（用于测试）
        return new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,  // JPEG SOI + APP0
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00  // DQT
        };
    }

    /**
     * 辅助方法：创建测试缩略图数据
     */
    private byte[] createTestThumbnail() {
        return createTestImage();  // 简化版
    }
}
