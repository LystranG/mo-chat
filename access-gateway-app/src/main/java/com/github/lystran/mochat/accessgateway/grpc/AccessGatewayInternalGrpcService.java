package com.github.lystran.mochat.accessgateway.grpc;

import com.github.lystran.mochat.accessgateway.runtime.LocalConnectionStateSnapshot;
import com.github.lystran.mochat.accessgateway.runtime.LocalGatewayConnectionDirectory;
import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import com.github.lystran.mochat.protocol.internal.gateway.v1.GetLocalConnectionStateRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.GetLocalConnectionStateResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.LocalConnectionState;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import io.netty.buffer.ByteBuf;

import java.util.Optional;

@Singleton
@Requires(property = "micronaut.application.name", value = "access-gateway")
public final class AccessGatewayInternalGrpcService extends AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiImplBase {
    private final UserChannelDirectory<Channel> userChannelDirectory;
    private final Provider<SessionResolver> sessionResolverProvider;

    public AccessGatewayInternalGrpcService(
        UserChannelDirectory<Channel> userChannelDirectory,
        Provider<SessionResolver> sessionResolverProvider
    ) {
        this.userChannelDirectory = userChannelDirectory;
        this.sessionResolverProvider = sessionResolverProvider;
    }

    @Override
    public void deliverToConnection(
        DeliverToConnectionRequest request,
        StreamObserver<DeliverToConnectionResponse> responseObserver
    ) {
        DeliveryStatus status;
        Optional<LocalConnectionStateSnapshot> localConnectionState = localConnectionDirectory()
            .flatMap(directory -> directory.findLocalConnectionState(request.getUserId(), request.getConnectionId()));
        if (localConnectionState.isEmpty()) {
            status = DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE;
        } else if (
            request.getSessionId().isBlank()
                || request.getSessionVersion() <= 0
                || request.getExpectedRouteEpoch() <= 0
                || !matchesLocalConnectionState(localConnectionState.orElseThrow(), request)
                || !matchesAuthoritativeSession(request)
        ) {
            status = DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE;
        } else if (!request.hasEnvelope() || request.getEnvelope().getMsgId() <= 0 || request.getEnvelope().getSeq() <= 0) {
            status = DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED;
        } else {
            status = deliverToLocalChannel(request);
        }
        responseObserver.onNext(DeliverToConnectionResponse.newBuilder()
            .setStatus(status)
            .setDetail(status.name().toLowerCase())
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void kickConnection(KickConnectionRequest request, StreamObserver<KickConnectionResponse> responseObserver) {
        boolean kicked = localConnectionDirectory()
            .map(directory -> directory.kickConnection(
                request.getUserId(),
                request.getConnectionId(),
                request.getSessionVersion(),
                request.getExpectedRouteEpoch(),
                request.getReason()
            ))
            .orElse(false);
        responseObserver.onNext(KickConnectionResponse.newBuilder()
            .setKicked(kicked)
            .setDetail(kicked ? "kicked" : "not_found_or_stale")
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getLocalConnectionState(
        GetLocalConnectionStateRequest request,
        StreamObserver<GetLocalConnectionStateResponse> responseObserver
    ) {
        GetLocalConnectionStateResponse.Builder response = GetLocalConnectionStateResponse.newBuilder();
        localConnectionDirectory()
            .flatMap(directory -> directory.findLocalConnectionState(request.getUserId(), request.getConnectionId()))
            .ifPresentOrElse(
                state -> applyLocalState(response, state),
                () -> response.setState(LocalConnectionState.LOCAL_CONNECTION_STATE_NOT_FOUND)
            );
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private Optional<LocalGatewayConnectionDirectory> localConnectionDirectory() {
        if (userChannelDirectory instanceof LocalGatewayConnectionDirectory localGatewayConnectionDirectory) {
            return Optional.of(localGatewayConnectionDirectory);
        }
        return Optional.empty();
    }

    private boolean matchesLocalConnectionState(
        LocalConnectionStateSnapshot localConnectionState,
        DeliverToConnectionRequest request
    ) {
        return localConnectionState.activeRouteOwner()
            && localConnectionState.sessionId().equals(request.getSessionId())
            && localConnectionState.sessionVersion() == request.getSessionVersion()
            && localConnectionState.routeEpoch() == request.getExpectedRouteEpoch();
    }

    private boolean matchesAuthoritativeSession(DeliverToConnectionRequest request) {
        try {
            var authoritativeSession = sessionResolverProvider.get().resolveAuthority(request.getSessionId());
            return authoritativeSession.status() == SessionAuthorityStatus.ACTIVE
                && authoritativeSession.userId() == request.getUserId()
                && authoritativeSession.sessionVersion() == request.getSessionVersion();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void applyLocalState(GetLocalConnectionStateResponse.Builder response, LocalConnectionStateSnapshot state) {
        response.setState(state.activeRouteOwner()
                ? LocalConnectionState.LOCAL_CONNECTION_STATE_BOUND
                : LocalConnectionState.LOCAL_CONNECTION_STATE_STALE)
            .setSessionId(state.sessionId())
            .setSessionVersion(state.sessionVersion())
            .setRouteEpoch(state.routeEpoch());
    }

    private DeliveryStatus deliverToLocalChannel(DeliverToConnectionRequest request) {
        Optional<Channel> targetChannel = userChannelDirectory.find(request.getUserId())
            .filter(Channel::isOpen)
            .filter(channel -> channel.id().asLongText().equals(request.getConnectionId()));
        if (targetChannel.isEmpty()) {
            return DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED;
        }

        ByteBuf frame = null;
        try {
            frame = encodeDeliveryFrame(targetChannel.orElseThrow(), request);
            ChannelFuture writeFuture = targetChannel.orElseThrow().writeAndFlush(frame).awaitUninterruptibly();
            if (!writeFuture.isSuccess()) {
                return DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED;
            }
            return DeliveryStatus.DELIVERY_STATUS_DELIVERED;
        } catch (RuntimeException writeFailure) {
            if (frame != null) {
                frame.release();
            }
            return DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED;
        }
    }

    private static ByteBuf encodeDeliveryFrame(Channel channel, DeliverToConnectionRequest request) {
        EncodedDelivery encodedDelivery = encodeDelivery(request);
        ByteBuf frame = channel.alloc().buffer(FrameConstants.HEADER_LENGTH + encodedDelivery.body().length);
        frame.writeInt(0x4D4F4348);
        frame.writeByte(FrameConstants.PROTOCOL_VERSION);
        frame.writeByte(encodedDelivery.msgType().code());
        frame.writeByte(SerializerType.PROTOBUF.code());
        frame.writeInt(encodedDelivery.body().length);
        frame.writeBytes(encodedDelivery.body());
        return frame;
    }

    private static EncodedDelivery encodeDelivery(DeliverToConnectionRequest request) {
        var envelope = request.getEnvelope();
        Mochat.ChatMessageDelivery.Builder delivery = Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(envelope.getMsgId())
            .setSeq(envelope.getSeq())
            .setServerTimeMs(envelope.getServerTimeMs())
            .setConversationId(envelope.getConversationId())
            .setFromUid(envelope.getFromUid());

        if (envelope.hasPrivateContent()) {
            return new EncodedDelivery(
                MsgType.PRIVATE_MESSAGE,
                delivery.setPrivatePayload(Mochat.PrivatePayload.newBuilder()
                    .setToUid(envelope.getPrivateContent().getToUid())
                    .setNonce(envelope.getPrivateContent().getNonce())
                    .setCiphertext(envelope.getPrivateContent().getCiphertext())
                    .build()).build().toByteArray()
            );
        }
        if (envelope.hasGroupContent()) {
            return new EncodedDelivery(
                MsgType.GROUP_MESSAGE,
                delivery.setGroupPayload(Mochat.GroupPayload.newBuilder()
                    .setGroupId(envelope.getGroupContent().getGroupId())
                    .setText(envelope.getGroupContent().getText())
                    .build()).build().toByteArray()
            );
        }
        throw new IllegalArgumentException("delivery envelope payload missing");
    }

    private record EncodedDelivery(MsgType msgType, byte[] body) {
    }
}
