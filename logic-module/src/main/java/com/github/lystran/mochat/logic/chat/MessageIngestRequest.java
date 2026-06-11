package com.github.lystran.mochat.logic.chat;

import java.util.Objects;

/**
 * 描述消息摄入主链路所需的统一入参，屏蔽私聊和群聊在上游协议上的差异。
 */
public record MessageIngestRequest(
    long senderUid,
    long conversationId,
    long clientMsgId,
    String kind,
    Long peerUidLow,
    Long peerUidHigh,
    Long groupId,
    String payloadBase64,
    MultimediaMetadata multimediaMetadata
) {
    public static final String KIND_PRIVATE = "PRIVATE";
    public static final String KIND_GROUP = "GROUP";

    public MessageIngestRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadBase64, "payloadBase64");
    }

    public static MessageIngestRequest privateMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long peerUidLow,
        long peerUidHigh,
        String payloadBase64
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_PRIVATE,
            peerUidLow,
            peerUidHigh,
            null,
            payloadBase64,
            null
        );
    }

    public static MessageIngestRequest groupMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long groupId,
        String payloadBase64
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_GROUP,
            null,
            null,
            groupId,
            payloadBase64,
            null
        );
    }

    public static MessageIngestRequest privateMultimediaMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long peerUidLow,
        long peerUidHigh,
        String payloadBase64,
        MultimediaMetadata multimediaMetadata
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_PRIVATE,
            peerUidLow,
            peerUidHigh,
            null,
            payloadBase64,
            Objects.requireNonNull(multimediaMetadata, "multimediaMetadata")
        );
    }

    public static MessageIngestRequest groupMultimediaMessage(
        long senderUid,
        long conversationId,
        long clientMsgId,
        long groupId,
        String payloadBase64,
        MultimediaMetadata multimediaMetadata
    ) {
        return new MessageIngestRequest(
            senderUid,
            conversationId,
            clientMsgId,
            KIND_GROUP,
            null,
            null,
            groupId,
            payloadBase64,
            Objects.requireNonNull(multimediaMetadata, "multimediaMetadata")
        );
    }

    public record MultimediaMetadata(
        String type,
        String mediaUrl,
        String thumbnailUrl,
        long fileSize,
        String mimeType,
        String fileName,
        Integer duration,
        Integer width,
        Integer height
    ) {
        public MultimediaMetadata {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(mediaUrl, "mediaUrl");
            if (fileSize <= 0) {
                throw new IllegalArgumentException("fileSize must be positive");
            }
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(fileName, "fileName");
        }
    }
}
