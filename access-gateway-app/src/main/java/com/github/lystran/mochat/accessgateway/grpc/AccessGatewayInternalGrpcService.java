package com.github.lystran.mochat.accessgateway.grpc;

import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import com.github.lystran.mochat.protocol.internal.gateway.v1.GetLocalConnectionStateRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.GetLocalConnectionStateResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.LocalConnectionState;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;

@Singleton
public final class AccessGatewayInternalGrpcService extends AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiImplBase {
    @Override
    public void deliverToConnection(
        DeliverToConnectionRequest request,
        StreamObserver<DeliverToConnectionResponse> responseObserver
    ) {
        DeliveryStatus status;
        if (request.getConnectionId().isBlank()) {
            status = DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE;
        } else if (request.getSessionId().isBlank() || request.getSessionVersion() <= 0 || request.getExpectedRouteEpoch() <= 0) {
            status = DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE;
        } else if (!request.hasEnvelope() || request.getEnvelope().getMsgId() <= 0 || request.getEnvelope().getSeq() <= 0) {
            status = DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED;
        } else {
            status = DeliveryStatus.DELIVERY_STATUS_DELIVERED;
        }
        responseObserver.onNext(DeliverToConnectionResponse.newBuilder()
            .setStatus(status)
            .setDetail(status.name().toLowerCase())
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void kickConnection(KickConnectionRequest request, StreamObserver<KickConnectionResponse> responseObserver) {
        responseObserver.onNext(KickConnectionResponse.newBuilder()
            .setKicked(!request.getConnectionId().isBlank())
            .setDetail("skeleton")
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getLocalConnectionState(
        GetLocalConnectionStateRequest request,
        StreamObserver<GetLocalConnectionStateResponse> responseObserver
    ) {
        GetLocalConnectionStateResponse.Builder response = GetLocalConnectionStateResponse.newBuilder();
        if (request.getConnectionId().isBlank()) {
            response.setState(LocalConnectionState.LOCAL_CONNECTION_STATE_NOT_FOUND);
        } else {
            response.setState(LocalConnectionState.LOCAL_CONNECTION_STATE_BOUND)
                .setSessionId("session-" + request.getUserId())
                .setSessionVersion(1L)
                .setRouteEpoch(1L);
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
