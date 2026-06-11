package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.MessageIngestRequest;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

@Controller("/messages")
public class MultimediaMessageController {

    private final MessageIngestService messageIngestService;
    private final IdGenerator idGenerator;
    private final SessionService sessionService;

    @Inject
    public MultimediaMessageController(
        MessageIngestService messageIngestService,
        IdGenerator idGenerator,
        SessionService sessionService
    ) {
        this.messageIngestService = Objects.requireNonNull(messageIngestService, "messageIngestService");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Post("/send-multimedia/private")
    public HttpResponse<?> sendPrivateMultimedia(@Body SendMultimediaRequest request) {
        var senderUidOpt = sessionService.resolveUserId(request.sessionId());
        if (senderUidOpt.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        
        long senderUid = senderUidOpt.get();
        long clientMsgId = idGenerator.nextId();
        long conversationId = calculateConversationId(senderUid, request.toUid());

        var multimediaMetadata = new MessageIngestRequest.MultimediaMetadata(
            request.messageType(),
            request.mediaUrl(),
            request.thumbnailUrl(),
            request.fileSize(),
            request.mimeType(),
            request.fileName(),
            request.duration(),
            request.width(),
            request.height()
        );

        var ingestRequest = MessageIngestRequest.privateMultimediaMessage(
            senderUid,
            conversationId,
            clientMsgId,
            Math.min(senderUid, request.toUid()),
            Math.max(senderUid, request.toUid()),
            request.payloadBase64(),
            multimediaMetadata
        );

        messageIngestService.ingest(ingestRequest);

        return HttpResponse.ok(Map.of(
            "success", true,
            "clientMsgId", clientMsgId,
            "conversationId", conversationId
        ));
    }

    @Post("/send-multimedia/group")
    public HttpResponse<?> sendGroupMultimedia(@Body SendMultimediaRequest request) {
        var senderUidOpt = sessionService.resolveUserId(request.sessionId());
        if (senderUidOpt.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        
        long senderUid = senderUidOpt.get();
        long clientMsgId = idGenerator.nextId();

        var multimediaMetadata = new MessageIngestRequest.MultimediaMetadata(
            request.messageType(),
            request.mediaUrl(),
            request.thumbnailUrl(),
            request.fileSize(),
            request.mimeType(),
            request.fileName(),
            request.duration(),
            request.width(),
            request.height()
        );

        var ingestRequest = MessageIngestRequest.groupMultimediaMessage(
            senderUid,
            request.conversationId(),
            clientMsgId,
            request.groupId(),
            request.payloadBase64(),
            multimediaMetadata
        );

        messageIngestService.ingest(ingestRequest);

        return HttpResponse.ok(Map.of(
            "success", true,
            "clientMsgId", clientMsgId,
            "conversationId", request.conversationId()
        ));
    }

    private long calculateConversationId(long uid1, long uid2) {
        return uid1 < uid2 ? uid1 * 1_000_000_000L + uid2 : uid2 * 1_000_000_000L + uid1;
    }

    public record SendMultimediaRequest(
        String sessionId,
        long toUid,
        Long groupId,
        long conversationId,
        String messageType,
        String mediaUrl,
        String thumbnailUrl,
        long fileSize,
        String mimeType,
        String fileName,
        Integer duration,
        Integer width,
        Integer height,
        String payloadBase64
    ) {
        public SendMultimediaRequest {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId cannot be null or blank");
            }
            if (messageType == null || messageType.isBlank()) {
                throw new IllegalArgumentException("messageType cannot be null or blank");
            }
            if (!isValidMessageType(messageType)) {
                throw new IllegalArgumentException("Invalid message type: " + messageType);
            }
            if (mediaUrl == null || mediaUrl.isBlank()) {
                throw new IllegalArgumentException("mediaUrl cannot be null or blank");
            }
            if (fileSize <= 0) {
                throw new IllegalArgumentException("fileSize must be positive");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType cannot be null or blank");
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName cannot be null or blank");
            }
            if (payloadBase64 == null || payloadBase64.isBlank()) {
                throw new IllegalArgumentException("payloadBase64 cannot be null or blank");
            }
        }

        private static boolean isValidMessageType(String type) {
            return "image".equals(type) || "video".equals(type) || 
                   "audio".equals(type) || "file".equals(type);
        }
    }
}
