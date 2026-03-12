package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionReplacementHandler;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import io.netty.channel.Channel;

import java.util.Map;
import java.util.Objects;

final class GatewayRouteReplacementHandler implements SessionReplacementHandler {
    private static final String REPLACED_BY_NEW_BIND = "replaced_by_new_bind";

    private final String gatewayPod;
    private final UserChannelDirectory<Channel> userChannelDirectory;
    private final AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory;
    private final Map<String, String> peerTargets;

    GatewayRouteReplacementHandler(
        String gatewayPod,
        UserChannelDirectory<Channel> userChannelDirectory,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        Map<String, String> peerTargets
    ) {
        this.gatewayPod = Objects.requireNonNull(gatewayPod, "gatewayPod");
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
        this.accessGatewayDispatchClientFactory = Objects.requireNonNull(accessGatewayDispatchClientFactory, "accessGatewayDispatchClientFactory");
        this.peerTargets = Map.copyOf(Objects.requireNonNull(peerTargets, "peerTargets"));
    }

    @Override
    public void handleReplacement(ResolvedSession newBinding, PersistedSessionRoute persistedRoute) {
        ReplacedSessionRoute replacedRoute = persistedRoute.replacedRoute();
        if (replacedRoute == null) {
            return;
        }
        if (gatewayPod.equals(replacedRoute.gatewayPod())) {
            kickLocally(newBinding, replacedRoute);
            return;
        }
        kickRemotely(newBinding, replacedRoute);
    }

    private void kickLocally(ResolvedSession newBinding, ReplacedSessionRoute replacedRoute) {
        if (!(userChannelDirectory instanceof LocalGatewayConnectionDirectory localGatewayConnectionDirectory)) {
            return;
        }
        localGatewayConnectionDirectory.kickConnection(
            newBinding.userId(),
            replacedRoute.connectionId(),
            replacedRoute.sessionVersion(),
            replacedRoute.routeEpoch(),
            REPLACED_BY_NEW_BIND
        );
    }

    private void kickRemotely(ResolvedSession newBinding, ReplacedSessionRoute replacedRoute) {
        String targetAddress = peerTargets.get(replacedRoute.gatewayPod());
        if (targetAddress == null || targetAddress.isBlank()) {
            return;
        }
        accessGatewayDispatchClientFactory.createClient(targetAddress)
            .kickConnection(KickConnectionRequest.newBuilder()
                .setUserId(newBinding.userId())
                .setConnectionId(replacedRoute.connectionId())
                .setSessionVersion(replacedRoute.sessionVersion())
                .setExpectedRouteEpoch(replacedRoute.routeEpoch())
                .setReason(REPLACED_BY_NEW_BIND)
                .build());
    }
}
