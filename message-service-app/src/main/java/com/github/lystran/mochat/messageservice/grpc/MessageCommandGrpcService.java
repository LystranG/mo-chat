package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.logic.chat.MessageIngestRequest;
import com.github.lystran.mochat.logic.chat.MessageIngestResult;
import com.github.lystran.mochat.logic.chat.MessageIngestService;
import com.github.lystran.mochat.logic.chat.MessageServiceOfflineReplayService;
import com.github.lystran.mochat.logic.chat.MessageRejectException;
import com.github.lystran.mochat.protocol.internal.message.v1.AcknowledgeReceiptAck;
import com.github.lystran.mochat.protocol.internal.message.v1.AcknowledgeReceiptCommand;
import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.ReplayOfflineMessagesAck;
import com.github.lystran.mochat.protocol.internal.message.v1.ReplayOfflineMessagesCommand;
import com.github.lystran.mochat.protocol.internal.message.v1.SendGroupMessageAck;
import com.github.lystran.mochat.protocol.internal.message.v1.SendGroupMessageCommand;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageAck;
import com.github.lystran.mochat.protocol.internal.message.v1.SendPrivateMessageCommand;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

import java.util.Base64;
import java.util.Objects;

@Singleton
public final class MessageCommandGrpcService extends MessageCommandApiGrpc.MessageCommandApiImplBase {
    private final MessageIngestService messageIngestService;
    private final MessageServiceOfflineReplayService messageServiceOfflineReplayService;

    public MessageCommandGrpcService(
        MessageIngestService messageIngestService,
        MessageServiceOfflineReplayService messageServiceOfflineReplayService
    ) {
        this.messageIngestService = Objects.requireNonNull(messageIngestService, "messageIngestService");
        this.messageServiceOfflineReplayService =
            Objects.requireNonNull(messageServiceOfflineReplayService, "messageServiceOfflineReplayService");
    }

    @Override
    public void sendPrivateMessage(
        SendPrivateMessageCommand request,
        StreamObserver<SendPrivateMessageAck> responseObserver
    ) {
        try {
            MessageIngestResult result = messageIngestService.ingest(MessageIngestRequest.privateMessage(
                request.getSenderUid(),
                request.getConversationId(),
                request.getClientMsgId(),
                Math.min(request.getSenderUid(), request.getRecipientUid()),
                Math.max(request.getSenderUid(), request.getRecipientUid()),
                Base64.getEncoder().encodeToString(com.github.lystran.mochat.protocol.proto.Mochat.PrivateMessageReq.newBuilder()
                    .setConversationId(request.getConversationId())
                    .setClientMsgId(request.getClientMsgId())
                    .setToUid(request.getRecipientUid())
                    .setNonce(request.getNonce())
                    .setCiphertext(request.getCiphertext())
                    .build()
                    .toByteArray())
            ));
            responseObserver.onNext(SendPrivateMessageAck.newBuilder()
                .setAccepted(true)
                .setClientMsgId(result.clientMsgId())
                .setMsgId(result.msgId())
                .setSeq(result.seq())
                .setServerTimeMs(result.serverTimeMs())
                .setDetail("accepted")
                .build());
        } catch (IllegalArgumentException rejection) {
            responseObserver.onNext(SendPrivateMessageAck.newBuilder()
                .setAccepted(false)
                .setClientMsgId(request.getClientMsgId())
                .setDetail(rejection.getMessage() == null ? "rejected" : rejection.getMessage())
                .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void sendGroupMessage(
        SendGroupMessageCommand request,
        StreamObserver<SendGroupMessageAck> responseObserver
    ) {
        try {
            MessageIngestResult result = messageIngestService.ingest(MessageIngestRequest.groupMessage(
                request.getSenderUid(),
                request.getConversationId(),
                request.getClientMsgId(),
                request.getGroupId(),
                Base64.getEncoder().encodeToString(com.github.lystran.mochat.protocol.proto.Mochat.GroupMessageReq.newBuilder()
                    .setConversationId(request.getConversationId())
                    .setClientMsgId(request.getClientMsgId())
                    .setGroupId(request.getGroupId())
                    .setText(request.getText())
                    .build()
                    .toByteArray())
            ));
            responseObserver.onNext(SendGroupMessageAck.newBuilder()
                .setAccepted(true)
                .setClientMsgId(result.clientMsgId())
                .setMsgId(result.msgId())
                .setSeq(result.seq())
                .setServerTimeMs(result.serverTimeMs())
                .setDetail("accepted")
                .build());
        } catch (IllegalArgumentException rejection) {
            responseObserver.onNext(SendGroupMessageAck.newBuilder()
                .setAccepted(false)
                .setClientMsgId(request.getClientMsgId())
                .setDetail(rejection.getMessage() == null ? "rejected" : rejection.getMessage())
                .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void replayOfflineMessages(
        ReplayOfflineMessagesCommand request,
        StreamObserver<ReplayOfflineMessagesAck> responseObserver
    ) {
        try {
            int replayedCount = messageServiceOfflineReplayService.replay(request.getUserId(), request.getMaxBatchSize());
            responseObserver.onNext(ReplayOfflineMessagesAck.newBuilder()
                .setAccepted(true)
                .setReplayedCount(replayedCount)
                .setDetail("replayed")
                .build());
        } catch (IllegalArgumentException rejection) {
            responseObserver.onNext(ReplayOfflineMessagesAck.newBuilder()
                .setAccepted(false)
                .setDetail(rejection.getMessage() == null ? "rejected" : rejection.getMessage())
                .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void acknowledgeReceipt(
        AcknowledgeReceiptCommand request,
        StreamObserver<AcknowledgeReceiptAck> responseObserver
    ) {
        responseObserver.onNext(AcknowledgeReceiptAck.newBuilder()
            .setAccepted(true)
            .setConversationId(request.getConversationId())
            .setLatestReceivedSeq(request.getLatestReceivedSeq())
            .setDetail("skeleton")
            .build());
        responseObserver.onCompleted();
    }
}
