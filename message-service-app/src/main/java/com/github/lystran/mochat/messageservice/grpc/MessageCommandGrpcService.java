package com.github.lystran.mochat.messageservice.grpc;

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

import java.time.Clock;

@Singleton
public final class MessageCommandGrpcService extends MessageCommandApiGrpc.MessageCommandApiImplBase {
    private final Clock clock = Clock.systemUTC();

    @Override
    public void sendPrivateMessage(
        SendPrivateMessageCommand request,
        StreamObserver<SendPrivateMessageAck> responseObserver
    ) {
        responseObserver.onNext(SendPrivateMessageAck.newBuilder()
            .setAccepted(true)
            .setClientMsgId(request.getClientMsgId())
            .setMsgId(1L)
            .setSeq(1L)
            .setServerTimeMs(clock.millis())
            .setDetail("skeleton")
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void sendGroupMessage(
        SendGroupMessageCommand request,
        StreamObserver<SendGroupMessageAck> responseObserver
    ) {
        responseObserver.onNext(SendGroupMessageAck.newBuilder()
            .setAccepted(true)
            .setClientMsgId(request.getClientMsgId())
            .setMsgId(1L)
            .setSeq(1L)
            .setServerTimeMs(clock.millis())
            .setDetail("skeleton")
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void replayOfflineMessages(
        ReplayOfflineMessagesCommand request,
        StreamObserver<ReplayOfflineMessagesAck> responseObserver
    ) {
        responseObserver.onNext(ReplayOfflineMessagesAck.newBuilder()
            .setAccepted(true)
            .setReplayedCount(0)
            .setDetail("skeleton")
            .build());
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
