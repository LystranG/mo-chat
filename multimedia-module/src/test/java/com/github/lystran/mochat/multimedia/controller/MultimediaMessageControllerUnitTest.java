package com.github.lystran.mochat.multimedia.controller;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.MessageIngestRequest;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.multimedia.dto.SendGroupMultimediaRequest;
import com.github.lystran.mochat.multimedia.dto.SendPrivateMultimediaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 多媒体消息 Controller 单元测试
 * 
 * 测试目标：
 * 1. 验证私聊多媒体消息发送流程
 * 2. 验证群聊多媒体消息发送流程
 * 3. 验证会话校验逻辑
 * 4. 验证 Protobuf 消息构建正确性
 */
class MultimediaMessageControllerUnitTest {

    @Mock
    private MessageIngestService messageIngestService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private SessionService sessionService;

    private MultimediaMessageController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // 设置 Mock 行为
        when(idGenerator.nextId()).thenReturn(123456789L);
        when(sessionService.resolveUserId("valid-session")).thenReturn(Optional.of(1001L));
        when(sessionService.resolveUserId("invalid-session")).thenReturn(Optional.empty());
        
        // 创建 Controller 实例
        controller = new MultimediaMessageController(messageIngestService, idGenerator, sessionService);
    }

    /**
     * 测试私聊多媒体消息发送成功场景
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnSuccess_WhenValidSessionAndMessage() {
        SendPrivateMultimediaRequest request = new SendPrivateMultimediaRequest(
                "valid-session",         // sessionId
                2001L,                   // toUid
                "image",                 // messageType
                "/media/images/test.jpg",  // mediaUrl
                "/media/images/test_thumb.jpg",  // thumbnailUrl
                102400L,                 // fileSize
                "image/jpeg",            // mimeType
                "photo.jpg",             // fileName
                null,                    // duration
                1920,                    // width
                1080,                    // height
                null                     // waveformData
        );

        ApiResponse<Map<String, Object>> response = controller.sendPrivateMultimedia(request);

        assertNotNull(response);
        assertTrue(response.success());
        
        Map<String, Object> data = response.data();
        assertNotNull(data);
        assertEquals(123456789L, data.get("clientMsgId"));
        assertNotNull(data.get("conversationId"));

        verify(messageIngestService, times(1)).ingest(any(MessageIngestRequest.class));
    }

    /**
     * 测试私聊多媒体消息发送失败场景 - 无效会话
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnError_WhenInvalidSession() {
        SendPrivateMultimediaRequest request = new SendPrivateMultimediaRequest(
                "invalid-session",
                2001L,
                "image",
                "/media/images/test.jpg",
                "/media/images/test_thumb.jpg",
                102400L,
                "image/jpeg",
                "photo.jpg",
                null,
                null,
                null,
                null
        );

        ApiResponse<Map<String, Object>> response = controller.sendPrivateMultimedia(request);

        assertNotNull(response);
        assertFalse(response.success());
        assertEquals("invalid session", response.message());

        verify(messageIngestService, never()).ingest(any(MessageIngestRequest.class));
    }

    /**
     * 测试群聊多媒体消息发送成功场景
     */
    @Test
    void sendGroupMultimedia_ShouldReturnSuccess_WhenValidSessionAndMessage() {
        SendGroupMultimediaRequest request = new SendGroupMultimediaRequest(
                "valid-session",         // sessionId
                3001L,                   // groupId
                1234567890123L,          // conversationId
                "video",                 // messageType
                "/media/videos/test.mp4",  // mediaUrl
                "/media/videos/test_cover.jpg",  // thumbnailUrl
                5242880L,                // fileSize
                "video/mp4",             // mimeType
                "video.mp4",             // fileName
                30000,                   // duration
                1920,                    // width
                1080,                    // height
                null                     // waveformData
        );

        ApiResponse<Map<String, Object>> response = controller.sendGroupMultimedia(request);

        assertNotNull(response);
        assertTrue(response.success());
        
        Map<String, Object> data = response.data();
        assertNotNull(data);
        assertEquals(123456789L, data.get("clientMsgId"));
        assertEquals(1234567890123L, data.get("conversationId"));

        verify(messageIngestService, times(1)).ingest(any(MessageIngestRequest.class));
    }

    /**
     * 测试群聊多媒体消息发送失败场景 - 无效会话
     */
    @Test
    void sendGroupMultimedia_ShouldReturnError_WhenInvalidSession() {
        SendGroupMultimediaRequest request = new SendGroupMultimediaRequest(
                "invalid-session",
                3001L,
                1234567890123L,
                "video",
                "/media/videos/test.mp4",
                "/media/videos/test_cover.jpg",
                5242880L,
                "video/mp4",
                "video.mp4",
                null,
                null,
                null,
                null
        );

        ApiResponse<Map<String, Object>> response = controller.sendGroupMultimedia(request);

        assertNotNull(response);
        assertFalse(response.success());
        assertEquals("invalid session", response.message());

        verify(messageIngestService, never()).ingest(any(MessageIngestRequest.class));
    }

    /**
     * 测试不同媒体类型的转换
     */
    @Test
    void sendPrivateMultimedia_ShouldHandleDifferentMediaTypes() {
        String[] mediaTypes = {"image", "video", "audio", "file"};

        for (String mediaType : mediaTypes) {
            SendPrivateMultimediaRequest request = new SendPrivateMultimediaRequest(
                    "valid-session",
                    2001L,
                    mediaType,
                    "/media/test.file",
                    "/media/test_cover.jpg",
                    1024L,
                    "application/octet-stream",
                    "test.file",
                    null,
                    null,
                    null,
                    null
            );

            ApiResponse<Map<String, Object>> response = controller.sendPrivateMultimedia(request);

            assertTrue(response.success(), "Failed for media type: " + mediaType);
        }
    }

    /**
     * 测试会话ID为空字符串的场景
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnError_WhenSessionIdIsEmpty() {
        // 空字符串会在 record 构造函数中抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            new SendPrivateMultimediaRequest(
                    "",  // empty sessionId - will fail validation in record constructor
                    2001L,
                    "image",
                    "/media/images/test.jpg",
                    null,
                    102400L,
                    "image/jpeg",
                    "photo.jpg",
                    null,
                    null,
                    null,
                    null
            );
        });
    }

    /**
     * 测试接收者UID无效的场景
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnError_WhenToUidIsInvalid() {
        // toUid <= 0 会在 record 构造函数中抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            new SendPrivateMultimediaRequest(
                    "valid-session",
                    0L,  // invalid toUid
                    "image",
                    "/media/images/test.jpg",
                    null,
                    102400L,
                    "image/jpeg",
                    "photo.jpg",
                    null,
                    null,
                    null,
                    null
            );
        });
    }

    /**
     * 测试消息类型无效的场景
     */
    @Test
    void sendPrivateMultimedia_ShouldReturnError_WhenMessageTypeIsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            new SendPrivateMultimediaRequest(
                    "valid-session",
                    2001L,
                    "invalid_type",  // invalid message type
                    "/media/images/test.jpg",
                    null,
                    102400L,
                    "image/jpeg",
                    "photo.jpg",
                    null,
                    null,
                    null,
                    null
            );
        });
    }
}
