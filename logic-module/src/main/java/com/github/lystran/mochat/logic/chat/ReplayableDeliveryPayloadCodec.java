package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

final class ReplayableDeliveryPayloadCodec {
    private ReplayableDeliveryPayloadCodec() {
    }

    static String encodePrivate(PrivateMessageDelivery delivery) {
        return encode(MsgType.PRIVATE_MESSAGE, buildPrivateMessage(delivery).toByteArray());
    }

    static String encodeGroup(GroupMessageDelivery delivery) {
        return encode(MsgType.GROUP_MESSAGE, buildGroupMessage(delivery).toByteArray());
    }

    private static String encode(MsgType msgType, byte[] payloadBytes) {
        return msgType.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(payloadBytes);
    }

    static boolean replayToRecipient(
        MessageRecipientDispatcher messageRecipientDispatcher,
        long recipientUid,
        String replayablePayload
    ) {
        Objects.requireNonNull(messageRecipientDispatcher, "messageRecipientDispatcher");
        if (recipientUid <= 0L) {
            return false;
        }

        String[] segments = replayablePayload.split("\\|", 3);
        if (segments.length != 3 || !SerializerType.PROTOBUF.name().equals(segments[1])) {
            return false;
        }

        MsgType msgType;
        try {
            msgType = MsgType.valueOf(segments[0]);
        } catch (IllegalArgumentException invalidType) {
            return false;
        }

        Mochat.ChatMessageDelivery delivery;
        try {
            delivery = Mochat.ChatMessageDelivery.parseFrom(Base64.getDecoder().decode(segments[2]));
        } catch (IllegalArgumentException | InvalidProtocolBufferException invalidPayload) {
            return false;
        }

        try {
            return switch (msgType) {
                case PRIVATE_MESSAGE -> messageRecipientDispatcher.dispatchPrivate(decodePrivate(delivery, recipientUid))
                    == MessageDeliveryStatus.DELIVERED;
                case GROUP_MESSAGE -> messageRecipientDispatcher.dispatchGroup(decodeGroup(delivery, recipientUid))
                    .getOrDefault(recipientUid, MessageDeliveryStatus.WRITE_FAILED) == MessageDeliveryStatus.DELIVERED;
                default -> false;
            };
        } catch (RuntimeException dispatchFailure) {
            return false;
        }
    }

    private static Mochat.ChatMessageDelivery buildPrivateMessage(PrivateMessageDelivery delivery) {
        return Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(delivery.msgId())
            .setSeq(delivery.seq())
            .setServerTimeMs(delivery.serverTimeMs())
            .setConversationId(delivery.conversationId())
            .setFromUid(delivery.senderUid())
            .setPrivatePayload(buildPrivatePayload(delivery))
            .build();
    }

    private static Mochat.ChatMessageDelivery buildGroupMessage(GroupMessageDelivery delivery) {
        return Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(delivery.msgId())
            .setSeq(delivery.seq())
            .setServerTimeMs(delivery.serverTimeMs())
            .setConversationId(delivery.conversationId())
            .setFromUid(delivery.senderUid())
            .setGroupPayload(buildGroupPayload(delivery))
            .build();
    }

    private static Mochat.PrivatePayload buildPrivatePayload(PrivateMessageDelivery delivery) {
        try {
            byte[] body = Base64.getDecoder().decode(delivery.payloadBase64());
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            return Mochat.PrivatePayload.newBuilder()
                .setToUid(delivery.recipientUid())
                .setNonce(privateRequest.getNonce())
                .setCiphertext(privateRequest.getCiphertext())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build private delivery payload", parseFailure);
        }
    }

    private static Mochat.GroupPayload buildGroupPayload(GroupMessageDelivery delivery) {
        try {
            byte[] body = Base64.getDecoder().decode(delivery.payloadBase64());
            var groupRequest = Mochat.GroupMessageReq.parseFrom(body);
            return Mochat.GroupPayload.newBuilder()
                .setGroupId(delivery.groupId())
                .setText(groupRequest.getText())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build group delivery payload", parseFailure);
        }
    }

    private static PrivateMessageDelivery decodePrivate(Mochat.ChatMessageDelivery delivery, long recipientUid) {
        if (!delivery.hasPrivatePayload()) {
            throw new IllegalArgumentException("missing private payload");
        }
        var request = Mochat.PrivateMessageReq.newBuilder()
            .setConversationId(delivery.getConversationId())
            .setToUid(recipientUid)
            .setNonce(delivery.getPrivatePayload().getNonce())
            .setCiphertext(delivery.getPrivatePayload().getCiphertext())
            .build();
        return new PrivateMessageDelivery(
            delivery.getConversationId(),
            delivery.getMsgId(),
            delivery.getSeq(),
            delivery.getServerTimeMs(),
            delivery.getFromUid(),
            recipientUid,
            Base64.getEncoder().encodeToString(request.toByteArray())
        );
    }

    private static GroupMessageDelivery decodeGroup(Mochat.ChatMessageDelivery delivery, long recipientUid) {
        if (!delivery.hasGroupPayload()) {
            throw new IllegalArgumentException("missing group payload");
        }
        var request = Mochat.GroupMessageReq.newBuilder()
            .setConversationId(delivery.getConversationId())
            .setGroupId(delivery.getGroupPayload().getGroupId())
            .setText(delivery.getGroupPayload().getText())
            .build();
        return new GroupMessageDelivery(
            delivery.getConversationId(),
            delivery.getMsgId(),
            delivery.getSeq(),
            delivery.getServerTimeMs(),
            delivery.getFromUid(),
            delivery.getGroupPayload().getGroupId(),
            Base64.getEncoder().encodeToString(request.toByteArray()),
            List.of(recipientUid)
        );
    }
}
