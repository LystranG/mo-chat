package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.connection.SessionBindingHandler;
import com.github.lystran.mochat.runtime.topology.GatewayAddressResolver;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRouteReplacementHandlerTest {
    @Test
    void localReplacementClosesMatchingBoundConnection() throws Exception {
        InMemoryUserChannelDirectory directory = new InMemoryUserChannelDirectory();
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        oldChannel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).set("active:42:7");
        oldChannel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).set(42L);
        oldChannel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).set(7L);
        oldChannel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(11L);
        oldChannel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
        directory.bind(42L, oldChannel);

        GatewayRouteReplacementHandler handler = new GatewayRouteReplacementHandler(
            "gateway-pod-a",
            directory,
            targetAddress -> request -> KickConnectionResponse.getDefaultInstance(),
            staticResolver(Map.of())
        );

        handler.handleReplacement(
            new ResolvedSession("active:42:8", 42L, 8L),
            new PersistedSessionRoute(12L, new ReplacedSessionRoute(
                "gateway-pod-a",
                oldChannel.id().asLongText(),
                "active:42:7",
                7L,
                11L
            ))
        );
        waitForPendingTasks(oldChannel);

        assertFalse(oldChannel.isOpen());
    }

    @Test
    void nonMatchingFenceDoesNotCloseConnection() throws Exception {
        InMemoryUserChannelDirectory directory = new InMemoryUserChannelDirectory();
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        oldChannel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).set("active:42:7");
        oldChannel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).set(42L);
        oldChannel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).set(7L);
        oldChannel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).set(11L);
        oldChannel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
        directory.bind(42L, oldChannel);

        GatewayRouteReplacementHandler handler = new GatewayRouteReplacementHandler(
            "gateway-pod-a",
            directory,
            targetAddress -> request -> KickConnectionResponse.getDefaultInstance(),
            staticResolver(Map.of())
        );

        handler.handleReplacement(
            new ResolvedSession("active:42:8", 42L, 8L),
            new PersistedSessionRoute(12L, new ReplacedSessionRoute(
                "gateway-pod-a",
                oldChannel.id().asLongText(),
                "active:42:7",
                7L,
                99L
            ))
        );
        waitForPendingTasks(oldChannel);

        assertTrue(oldChannel.isOpen());
    }

    @Test
    void remoteReplacementUsesConfiguredPeerTarget() {
        InMemoryUserChannelDirectory directory = new InMemoryUserChannelDirectory();
        AtomicReference<String> targetAddress = new AtomicReference<>();
        AtomicReference<KickConnectionRequest> requestRef = new AtomicReference<>();
        GatewayRouteReplacementHandler handler = new GatewayRouteReplacementHandler(
            "gateway-pod-a",
            directory,
            new RecordingAccessGatewayDispatchClientFactory(targetAddress, requestRef),
            staticResolver(Map.of("gateway-pod-b", "dns:///gateway-pod-b:19093"))
        );

        handler.handleReplacement(
            new ResolvedSession("active:42:8", 42L, 8L),
            new PersistedSessionRoute(12L, new ReplacedSessionRoute(
                "gateway-pod-b",
                "conn-remote-1",
                "active:42:7",
                7L,
                11L
            ))
        );

        assertTrue(targetAddress.get().contains("gateway-pod-b:19093"));
        assertTrue(requestRef.get().getReason().contains("replaced_by_new_bind"));
        assertTrue(requestRef.get().getConnectionId().contains("conn-remote-1"));
    }

    private static void waitForPendingTasks(EmbeddedChannel channel) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }

    private static GatewayAddressResolver staticResolver(Map<String, String> gatewayTargets) {
        return gatewayTargets::get;
    }

    private static final class RecordingAccessGatewayDispatchClientFactory implements AccessGatewayDispatchClientFactory {
        private final AtomicReference<String> targetAddress;
        private final AtomicReference<KickConnectionRequest> requestRef;

        private RecordingAccessGatewayDispatchClientFactory(
            AtomicReference<String> targetAddress,
            AtomicReference<KickConnectionRequest> requestRef
        ) {
            this.targetAddress = targetAddress;
            this.requestRef = requestRef;
        }

        @Override
        public AccessGatewayDispatchClient createClient(String targetAddress) {
            this.targetAddress.set(targetAddress);
            return request -> {
                requestRef.set(request);
                return KickConnectionResponse.newBuilder()
                    .setKicked(true)
                    .setDetail("ok")
                    .build();
            };
        }
    }
}
