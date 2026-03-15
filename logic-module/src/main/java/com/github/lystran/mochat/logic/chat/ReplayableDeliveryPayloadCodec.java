package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 负责把“离线补发要用的消息”编码成字符串，并在需要时再还原出来。
 */
final class ReplayableDeliveryPayloadCodec {
    /**
     * 工具类不需要实例。
     */
    private ReplayableDeliveryPayloadCodec() {
    }

    /**
     * 把私聊消息改成可放进离线队列的一条字符串消息。
     */
    static String encodePrivate(PrivateMessageDelivery delivery) {
        return encode(MsgType.PRIVATE_MESSAGE, buildPrivateMessage(delivery).toByteArray());
    }

    /**
     * 把群消息改成可放进离线队列的一条字符串消息。
     */
    static String encodeGroup(GroupMessageDelivery delivery) {
        return encode(MsgType.GROUP_MESSAGE, buildGroupMessage(delivery).toByteArray());
    }

    /**
     * 用“消息类型 + 竖线 + 序列化方式 + 竖线 + Base64 消息正文”的格式编码。
     */
    private static String encode(MsgType msgType, byte[] payloadBytes) {
        return msgType.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(payloadBytes);
    }

    /**
     * 登录后把之前没收到的消息重新发给指定用户。
     */
    static boolean replayToRecipient(
        MessageRecipientDispatcher messageRecipientDispatcher,
        long recipientUid,
        String replayablePayload
    ) {
        Objects.requireNonNull(messageRecipientDispatcher, "messageRecipientDispatcher");
        if (recipientUid <= 0L) {
            return false;
        }

        // 离线队列里放的是三段竖线字符串，先拆出消息类型、序列化方式和消息正文。
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
            // 这里会把离线字符串重新还原成业务对象，再走一次正常的在线投递。
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

    /**
     * 把私聊对象整理成统一的内部投递消息。
     */
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

    /**
     * 把群聊对象整理成统一的内部投递消息。
     */
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

    /**
     * 从私聊原始消息正文里提取在线投递需要的字段。
     */
    private static Mochat.PrivatePayload buildPrivatePayload(PrivateMessageDelivery delivery) {
        try {
            // 这里拿到的是去掉 sessionId 后的消息正文，只保留真正投递给对方需要的内容。
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

    /**
     * 从群聊原始消息正文里提取在线投递需要的字段。
     */
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

    /**
     * 把离线字符串里的私聊消息还原成可再次投递的私聊对象。
     */
    private static PrivateMessageDelivery decodePrivate(Mochat.ChatMessageDelivery delivery, long recipientUid) {
        if (!delivery.hasPrivatePayload()) {
            throw new IllegalArgumentException("missing private payload");
        }
        // 重新补发时，消息正文会重新打包成接收方能理解的私聊请求格式。
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

    /**
     * 把离线字符串里的群消息还原成只投给当前用户的一条群消息。
     */
    private static GroupMessageDelivery decodeGroup(Mochat.ChatMessageDelivery delivery, long recipientUid) {
        if (!delivery.hasGroupPayload()) {
            throw new IllegalArgumentException("missing group payload");
        }
        // 群消息补发时，会把收件人列表收窄成当前这一个用户。
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
