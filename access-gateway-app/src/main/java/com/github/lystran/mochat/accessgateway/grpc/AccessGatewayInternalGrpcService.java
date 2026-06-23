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
/**
 * access-gateway 对外暴露的内部 gRPC 服务，供别的服务把消息发到真正持有连接的网关实例。
 */
public final class AccessGatewayInternalGrpcService extends AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiImplBase {
    private final UserChannelDirectory<Channel> userChannelDirectory;
    private final Provider<SessionResolver> sessionResolverProvider;

    /**
     * 组装本地连接目录和会话权威查询入口。
     */
    public AccessGatewayInternalGrpcService(
        UserChannelDirectory<Channel> userChannelDirectory,
        Provider<SessionResolver> sessionResolverProvider
    ) {
        this.userChannelDirectory = userChannelDirectory;
        this.sessionResolverProvider = sessionResolverProvider;
    }

    @Override
    /**
     * 把消息投给当前网关真正持有的那条连接；如果发现路由已经过期，就明确返回 stale。
     */
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
    /**
     * 按用户、连接、会话版本和路由版本精确关闭一条旧连接。
     */
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
    /**
     * 返回当前网关里这条连接的实时状态，供跨网关投递前做更细的判断。
     */
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

    /**
     * 只有连接目录支持本地精确查询和踢连接时，才返回本地目录能力。
     */
    private Optional<LocalGatewayConnectionDirectory> localConnectionDirectory() {
        if (userChannelDirectory instanceof LocalGatewayConnectionDirectory localGatewayConnectionDirectory) {
            return Optional.of(localGatewayConnectionDirectory);
        }
        return Optional.empty();
    }

    /**
     * 检查本机这条连接的会话和路由版本，确认请求说的就是当前这条有效连接。
     */
    private boolean matchesLocalConnectionState(
        LocalConnectionStateSnapshot localConnectionState,
        DeliverToConnectionRequest request
    ) {
        return localConnectionState.activeRouteOwner()
            && localConnectionState.sessionId().equals(request.getSessionId())
            && localConnectionState.sessionVersion() == request.getSessionVersion()
            && localConnectionState.routeEpoch() == request.getExpectedRouteEpoch();
    }

    /**
     * 再向会话权威确认一次，避免已经失效的 session 还被继续投递。
     */
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

    /**
     * 把本地连接快照写进 gRPC 响应。
     */
    private void applyLocalState(GetLocalConnectionStateResponse.Builder response, LocalConnectionStateSnapshot state) {
        response.setState(state.activeRouteOwner()
                ? LocalConnectionState.LOCAL_CONNECTION_STATE_BOUND
                : LocalConnectionState.LOCAL_CONNECTION_STATE_STALE)
            .setSessionId(state.sessionId())
            .setSessionVersion(state.sessionVersion())
            .setRouteEpoch(state.routeEpoch());
    }

    /**
     * 在本机再次确认目标连接还开着，随后把消息写进对应 Channel。
     */
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

    /**
     * 把内部投递请求编码成客户端能直接收到的聊天协议帧。
     */
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

    /**
     * 按投递内容是私聊还是群聊，构造对应的客户端消息体。
     */
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
                    .addAllContents(envelope.getPrivateContent().getContentsList())
                    .build()).build().toByteArray()
            );
        }
        if (envelope.hasGroupContent()) {
            return new EncodedDelivery(
                MsgType.GROUP_MESSAGE,
                delivery.setGroupPayload(Mochat.GroupPayload.newBuilder()
                    .setGroupId(envelope.getGroupContent().getGroupId())
                    .addAllContents(envelope.getGroupContent().getContentsList())
                    .build()).build().toByteArray()
            );
        }
        throw new IllegalArgumentException("delivery envelope payload missing");
    }

    /**
     * 一次已经编码好的本地投递结果。
     *
     * @param msgType 要发给客户端的消息类型
     * @param body 编码后的消息体
     */
    private record EncodedDelivery(MsgType msgType, byte[] body) {
    }
}
