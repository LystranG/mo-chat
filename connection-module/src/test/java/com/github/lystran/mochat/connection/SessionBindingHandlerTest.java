package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.session.SessionResolutionException;
import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ReplacedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionReplacementHandler;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBindingHandlerTest {
    @Test
    void heartbeatTimeoutCleansChannelAndSessionBinding() {
        RecordingRegistry registry = new RecordingRegistry();
        SessionResolver sessionResolver = sessionId -> "session-42".equals(sessionId)
            ? Optional.of(new ResolvedSession(sessionId, 42L, 1L))
            : Optional.empty();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 1),
            new SessionBindingHandler(sessionResolver, registry)
        );

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));

        assertEquals("session-42", registry.sessionId);
        assertEquals(42L, registry.userId);
        assertSame(channel, registry.channel);
        assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void nonHeartbeatTrafficDoesNotRefreshHeartbeatTimeout() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler(1, 1));

        channel.advanceTimeBy(900, TimeUnit.MILLISECONDS);
        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));
        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void heartbeatAckRenewsPersistedRouteForActiveOwner() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingHeartbeatRouteWriter routeWriter = new RecordingHeartbeatRouteWriter(17L, true);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            new SessionBindingHandler(
                fixedResolvedSessionResolver(),
                registry,
                routeWriter
            ),
            downstream
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_HEARTBEAT,
            SerializerType.PROTOBUF,
            new byte[0]
        ));

        assertTrue(channel.isOpen());
        assertEquals(1, routeWriter.renewCount());
        assertEquals(0, routeWriter.clearCount());
        assertEquals("active:42:7", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void heartbeatAckClosesChannelWhenRenewalDetectsStaleRoute() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingHeartbeatRouteWriter routeWriter = new RecordingHeartbeatRouteWriter(17L, false);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            new SessionBindingHandler(
                fixedResolvedSessionResolver(),
                registry,
                routeWriter
            ),
            downstream
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_HEARTBEAT,
            SerializerType.PROTOBUF,
            new byte[0]
        ));
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(1, routeWriter.renewCount());
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
    }

    @Test
    void heartbeatAckClosesChannelWhenAuthoritySessionIsNoLongerActive() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingHeartbeatRouteWriter routeWriter = new RecordingHeartbeatRouteWriter(17L, true);
        MutableSessionResolver sessionResolver = new MutableSessionResolver(
            Optional.of(new ResolvedSession("active:42:7", 42L, 7L))
        );
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 5),
            new SessionBindingHandler(
                sessionResolver,
                registry,
                routeWriter
            ),
            downstream
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        sessionResolver.setResolvedSession(Optional.empty());

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_HEARTBEAT,
            SerializerType.PROTOBUF,
            new byte[0]
        ));
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(0, routeWriter.renewCount());
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
    }

    @Test
    void heartbeatTimeoutClearsPersistedRouteBeforeClosingOwnedConnection() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        RecordingHeartbeatRouteWriter routeWriter = new RecordingHeartbeatRouteWriter(17L, true);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HeartbeatHandler(1, 1),
            new SessionBindingHandler(
                fixedResolvedSessionResolver(),
                registry,
                routeWriter
            ),
            downstream
        );

        channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(1, routeWriter.clearCount());
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void heartbeatTimeoutClearsPersistedRouteOffChannelEventLoopWhenExecutorConfigured() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        AtomicReference<String> clearThreadName = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-bind-worker");
            thread.setDaemon(true);
            return thread;
        })) {
            SessionRouteWriter<Channel> routeWriter = new SessionRouteWriter<>() {
                @Override
                public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
                    return new PersistedSessionRoute(17L);
                }

                @Override
                public boolean clearRoute(
                    ResolvedSession resolvedSession,
                    Channel channelRef,
                    PersistedSessionRoute persistedRoute
                ) {
                    clearThreadName.set(Thread.currentThread().getName());
                    return true;
                }
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new HeartbeatHandler(1, 1),
                new SessionBindingHandler(
                    fixedResolvedSessionResolver(),
                    registry,
                    routeWriter,
                    executor
                ),
                new RecordingInboundHandler()
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            channel.advanceTimeBy(2, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            waitForPendingTasks(channel);

            assertEquals("session-bind-worker", clearThreadName.get());
            assertFalse(channel.isOpen());
            assertTrue(registry.empty);
        }
    }

    @Test
    void heartbeatTimeoutRemovesLocalOwnershipBeforeAsyncRouteCleanupFinishes() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        CountDownLatch clearStarted = new CountDownLatch(1);
        CountDownLatch releaseClear = new CountDownLatch(1);
        DelayedCloseHandler delayedCloseHandler = new DelayedCloseHandler();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-bind-worker");
            thread.setDaemon(true);
            return thread;
        })) {
            SessionRouteWriter<Channel> routeWriter = new SessionRouteWriter<>() {
                @Override
                public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
                    return new PersistedSessionRoute(17L);
                }

                @Override
                public boolean clearRoute(
                    ResolvedSession resolvedSession,
                    Channel channelRef,
                    PersistedSessionRoute persistedRoute
                ) {
                    clearStarted.countDown();
                    try {
                        releaseClear.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruptedException);
                    }
                    return true;
                }
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                delayedCloseHandler,
                new HeartbeatHandler(1, 1),
                new SessionBindingHandler(
                    fixedResolvedSessionResolver(),
                    registry,
                    routeWriter,
                    executor
                ),
                new RecordingInboundHandler()
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            channel.advanceTimeBy(2, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(clearStarted.await(1, TimeUnit.SECONDS));
            assertTrue(delayedCloseHandler.awaitCloseIntercepted());

            assertTrue(registry.empty);
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
            assertFalse(SessionBindingHandler.hasActiveRouteOwnership(channel));

            releaseClear.countDown();
            delayedCloseHandler.releaseClose();
            waitForPendingTasks(channel);
        }
    }

    @Test
    void heartbeatRenewalRunsOffChannelEventLoopWhenExecutorConfigured() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicReference<String> renewThreadName = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-bind-worker");
            thread.setDaemon(true);
            return thread;
        })) {
            SessionRouteWriter<Channel> routeWriter = new SessionRouteWriter<>() {
                @Override
                public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
                    return new PersistedSessionRoute(17L);
                }

                @Override
                public boolean renewRoute(
                    ResolvedSession resolvedSession,
                    Channel channelRef,
                    PersistedSessionRoute persistedRoute
                ) {
                    renewThreadName.set(Thread.currentThread().getName());
                    return true;
                }
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new HeartbeatHandler(1, 5),
                new SessionBindingHandler(
                    fixedResolvedSessionResolver(),
                    registry,
                    routeWriter,
                    executor
                ),
                downstream
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            channel.writeInbound(new InboundRouterHandler.InboundMessage(
                MsgType.CLIENT_HEARTBEAT,
                SerializerType.PROTOBUF,
                new byte[0]
            ));
            waitForPendingTasks(channel);

            assertEquals("session-bind-worker", renewThreadName.get());
            assertTrue(channel.isOpen());
        }
    }

    @Test
    void staleHeartbeatRenewalResultDoesNotCloseChannelAfterNewBindCompletes() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        CountDownLatch renewStarted = new CountDownLatch(1);
        CountDownLatch releaseRenew = new CountDownLatch(1);
        try (AsyncTestExecutor executor = new AsyncTestExecutor()) {
            SessionRouteWriter<Channel> routeWriter = new SessionRouteWriter<>() {
                @Override
                public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
                    return new PersistedSessionRoute("active:42:8".equals(resolvedSession.sessionId()) ? 18L : 17L);
                }

                @Override
                public boolean renewRoute(
                    ResolvedSession resolvedSession,
                    Channel channelRef,
                    PersistedSessionRoute persistedRoute
                ) {
                    if ("active:42:7".equals(resolvedSession.sessionId())) {
                        renewStarted.countDown();
                        try {
                            releaseRenew.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interruptedException);
                        }
                        return false;
                    }
                    return true;
                }
            };
            SessionResolver sessionResolver = new SessionResolver() {
                @Override
                public Optional<ResolvedSession> resolveSession(String sessionId) {
                    return switch (sessionId) {
                        case "active:42:7" -> Optional.of(new ResolvedSession("active:42:7", 42L, 7L));
                        case "active:42:8" -> Optional.of(new ResolvedSession("active:42:8", 42L, 8L));
                        default -> Optional.empty();
                    };
                }

                @Override
                public Optional<Long> resolveUserId(String sessionId) {
                    return resolveSession(sessionId).map(ResolvedSession::userId);
                }
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new HeartbeatHandler(1, 5),
                new SessionBindingHandler(
                    sessionResolver,
                    registry,
                    routeWriter,
                    executor,
                    64
                ),
                new RecordingInboundHandler()
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            channel.writeInbound(new InboundRouterHandler.InboundMessage(
                MsgType.CLIENT_HEARTBEAT,
                SerializerType.PROTOBUF,
                new byte[0]
            ));
            assertTrue(renewStarted.await(1, TimeUnit.SECONDS));

            channel.writeInbound(privateMessage("active:42:8", 201L, 89L));
            waitForPendingTasks(channel);

            assertTrue(channel.isOpen());
            assertEquals("active:42:8", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(8L, channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertEquals(18L, channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());

            releaseRenew.countDown();
            waitForPendingTasks(channel);

            assertTrue(channel.isOpen());
            assertEquals("active:42:8", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(8L, channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertEquals(18L, channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
        }
    }

    @Test
    void firstPrivateMessageWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        ));
    }

    @Test
    void invalidSessionAfterBindingClearsBindingReturnsErrorAndStopsRouting() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        SessionResolver sessionResolver = sessionId -> "session-42".equals(sessionId)
            ? Optional.of(new ResolvedSession(sessionId, 42L, 1L))
            : Optional.empty();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionResolver, registry),
            downstream
        );

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("session-42")
                .setClientMsgId(1001L)
                .setConversationId(200L)
                .setToUid(88L)
                .build()
                .toByteArray()
        ));

        assertEquals(1, downstream.messageCount);
        assertEquals("session-42", registry.sessionId);
        assertEquals(42L, registry.userId);
        assertSame(channel, registry.channel);
        assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertNull(channel.readOutbound());

        channel.writeInbound(new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(1002L)
                .setConversationId(201L)
                .setToUid(89L)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        ));

        assertEquals(1, downstream.messageCount);
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertSessionInvalidResponse(channel);
        assertNull(channel.readInbound());
    }

    @Test
    void firstGroupMessageWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.GROUP_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.GroupMessageReq.newBuilder()
                .setSessionId("invalid-session")
                .setClientMsgId(2002L)
                .setConversationId(300L)
                .setGroupId(300L)
                .setText("hello-group")
                .build()
                .toByteArray()
        ));
    }

    @Test
    void firstReceiptWithInvalidSessionReturnsAuthFailureAndStopsRouting() throws Exception {
        assertFirstInvalidSessionReturnsAuthFailure(new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_RECEIVE_ACK,
            SerializerType.PROTOBUF,
            Mochat.ClientReceiveAck.newBuilder()
                .setSessionId("invalid-session")
                .setConversationId(200L)
                .setLatestReceivedSeq(66L)
                .build()
                .toByteArray()
        ));
    }

    @Test
    void sameBoundSessionRevalidatesAuthorityForSubsequentChatTraffic() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicInteger resolveCount = new AtomicInteger();
        SessionResolver sessionResolver = sessionId -> {
            resolveCount.incrementAndGet();
            return "session-42".equals(sessionId)
                ? Optional.of(new ResolvedSession(sessionId, 42L, 1L))
                : Optional.empty();
        };
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionResolver, registry),
            downstream
        );

        channel.writeInbound(privateMessage("session-42", 200L, 88L));
        channel.writeInbound(groupMessage("session-42", 300L, 300L));
        channel.writeInbound(receiptAck("session-42", 200L, 66L));

        assertEquals(3, resolveCount.get());
        assertEquals(3, downstream.messageCount);
        assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void replacedBoundSessionIsRejectedOnSubsequentChatTraffic() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicInteger resolveCount = new AtomicInteger();
        SessionResolver sessionResolver = sessionId -> {
            int attempt = resolveCount.incrementAndGet();
            if (!"session-42".equals(sessionId)) {
                return Optional.empty();
            }
            return attempt == 1 ? Optional.of(new ResolvedSession(sessionId, 42L, 1L)) : Optional.empty();
        };
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionResolver, registry),
            downstream
        );

        channel.writeInbound(privateMessage("session-42", 200L, 88L));
        channel.writeInbound(groupMessage("session-42", 300L, 300L));

        assertEquals(2, resolveCount.get());
        assertEquals(1, downstream.messageCount);
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertErrorResponse(channel, ErrorCode.SESSION_INVALID.code(), "session invalid");
        assertNull(channel.readInbound());
    }

    @Test
    void sessionChangeReResolvesAndRebinds() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicInteger resolveCount = new AtomicInteger();
        SessionResolver sessionResolver = sessionId -> {
            resolveCount.incrementAndGet();
            return switch (sessionId) {
                case "session-42" -> Optional.of(new ResolvedSession(sessionId, 42L, 1L));
                case "session-52" -> Optional.of(new ResolvedSession(sessionId, 52L, 1L));
                default -> Optional.empty();
            };
        };
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionResolver, registry),
            downstream
        );

        channel.writeInbound(privateMessage("session-42", 200L, 88L));
        channel.writeInbound(privateMessage("session-52", 201L, 89L));

        assertEquals(2, resolveCount.get());
        assertEquals(2, downstream.messageCount);
        assertEquals("session-52", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertEquals(52L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
    }

    @Test
    void upstreamResolutionFailureReturnsInternalErrorInsteadOfSessionInvalid() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(
                sessionId -> {
                    throw new SessionResolutionException("api-service unavailable");
                },
                registry
            ),
            downstream
        );

        channel.writeInbound(privateMessage("session-42", 200L, 88L));

        assertEquals(0, downstream.messageCount);
        assertTrue(registry.empty);
        assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code(), "session resolution unavailable");
        assertNull(channel.readInbound());
    }

    @Test
    void asyncResolutionDoesNotBlockWriteInboundAndResumesAfterResolve() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch resolveEntered = new CountDownLatch(1);
        CountDownLatch unblockResolve = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionResolver sessionResolver = sessionId -> {
                resolveEntered.countDown();
                try {
                    unblockResolve.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruptedException);
                }
                return Optional.of(new ResolvedSession(sessionId, 42L, 1L));
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(sessionResolver, registry, executor),
                downstream
            );

            long startedAt = System.nanoTime();
            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(resolveEntered.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMillis < 200, "writeInbound should not wait for remote session resolution");
            assertEquals(0, downstream.messageCount);

            unblockResolve.countDown();
            waitForPendingTasks(channel);

            assertEquals(1, downstream.messageCount);
            assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        }
    }

    @Test
    void asyncSessionSwitchQueuesMessagesAndPreservesOrder() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingPayloadHandler downstream = new RecordingPayloadHandler();
        CountDownLatch resolveEntered = new CountDownLatch(1);
        CountDownLatch unblockResolve = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionResolver sessionResolver = sessionId -> {
                if ("session-42".equals(sessionId)) {
                    return Optional.of(new ResolvedSession(sessionId, 42L, 1L));
                }
                if ("session-52".equals(sessionId)) {
                    resolveEntered.countDown();
                    try {
                        unblockResolve.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruptedException);
                    }
                    return Optional.of(new ResolvedSession(sessionId, 52L, 1L));
                }
                return Optional.empty();
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(sessionResolver, registry, executor),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            waitForPendingTasks(channel);
            assertEquals(List.of("PRIVATE_MESSAGE:200"), downstream.events);

            channel.writeInbound(privateMessage("session-52", 201L, 89L));
            channel.writeInbound(receiptAck("session-52", 201L, 77L));

            assertTrue(resolveEntered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("PRIVATE_MESSAGE:200"), downstream.events);

            unblockResolve.countDown();
            waitForPendingTasks(channel);

            assertEquals(
                List.of("PRIVATE_MESSAGE:200", "PRIVATE_MESSAGE:201", "CLIENT_RECEIVE_ACK:201"),
                downstream.events
            );
            assertEquals("session-52", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(52L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        }
    }

    @Test
    void queuedDifferentSessionDoesNotReuseTrustedBindingFromCompletedAsyncBind() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingPayloadHandler downstream = new RecordingPayloadHandler();
        CountDownLatch session42ResolveEntered = new CountDownLatch(1);
        CountDownLatch session52ResolveEntered = new CountDownLatch(1);
        CountDownLatch unblockSession42Resolve = new CountDownLatch(1);
        CountDownLatch unblockSession52Resolve = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionResolver sessionResolver = sessionId -> {
                switch (sessionId) {
                    case "session-42" -> {
                        session42ResolveEntered.countDown();
                        try {
                            unblockSession42Resolve.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interruptedException);
                        }
                        return Optional.of(new ResolvedSession(sessionId, 42L, 1L));
                    }
                    case "session-52" -> {
                        session52ResolveEntered.countDown();
                        try {
                            unblockSession52Resolve.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interruptedException);
                        }
                        return Optional.of(new ResolvedSession(sessionId, 52L, 1L));
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(sessionResolver, registry, executor),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            assertTrue(session42ResolveEntered.await(1, TimeUnit.SECONDS));

            channel.writeInbound(privateMessage("session-52", 201L, 89L));
            assertEquals(List.of(), downstream.events);

            unblockSession42Resolve.countDown();
            waitForPendingTasks(channel);

            assertTrue(session52ResolveEntered.getCount() == 0L);
            assertEquals(List.of("PRIVATE_MESSAGE:200"), downstream.events);
            assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());

            unblockSession52Resolve.countDown();
            waitForPendingTasks(channel);

            assertEquals(List.of("PRIVATE_MESSAGE:200", "PRIVATE_MESSAGE:201"), downstream.events);
            assertEquals("session-52", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(52L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        }
    }

    @Test
    void asyncRouteWriteRunsOffChannelEventLoop() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicReference<String> routeWriteThreadName = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-bind-worker");
            thread.setDaemon(true);
            return thread;
        })) {
            SessionRouteWriter<Channel> routeWriter = (resolvedSession, channelRef) -> {
                routeWriteThreadName.set(Thread.currentThread().getName());
                return new PersistedSessionRoute(17L);
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(
                    sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 1L)),
                    registry,
                    routeWriter,
                    executor
                ),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            waitForPendingTasks(channel);

            assertEquals("session-bind-worker", routeWriteThreadName.get());
            assertEquals(1, downstream.messageCount);
            assertEquals("session-42", channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertEquals(42L, channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertEquals(17L, channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
        }
    }

    @Test
    void drainingDuringAsyncBindRollsBackPartiallyWrittenRouteAndClosesChannel() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch routePersisted = new CountDownLatch(1);
        CountDownLatch unblockRouteWrite = new CountDownLatch(1);
        MutableDrainState gatewayDrainState = new MutableDrainState();
        StagedRouteWriter routeWriter = new StagedRouteWriter(routePersisted, unblockRouteWrite, null, false);
        try (AsyncTestExecutor executor = new AsyncTestExecutor()) {
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(
                    fixedResolvedSessionResolver(),
                    registry,
                    routeWriter,
                    SessionReplacementHandler.noop(),
                    gatewayDrainState,
                    executor,
                    64
                ),
                downstream
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            assertTrue(routePersisted.await(1, TimeUnit.SECONDS));

            gatewayDrainState.startDrain();
            unblockRouteWrite.countDown();
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertEquals(0, downstream.messageCount);
            assertTrue(routeWriter.routePersisted());
            assertTrue(routeWriter.routeCleared());
            assertEquals(1, routeWriter.writeCount());
            assertEquals(1, routeWriter.clearCount());
            assertTrue(registry.empty);
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
            assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code(), "gateway draining");
        }
    }

    @Test
    void bindTriggersReplacementHandlerWhenRouteWriteReplacesExistingOwner() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        AtomicReference<ResolvedSession> replacementBinding = new AtomicReference<>();
        AtomicReference<PersistedSessionRoute> replacementRoute = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRouteWriter<Channel> routeWriter = (resolvedSession, channelRef) -> new PersistedSessionRoute(
                17L,
                new ReplacedSessionRoute("gateway-pod-a", "old-conn", "active:42:6", 6L, 16L)
            );
            SessionReplacementHandler replacementHandler = (newBinding, persistedRoute) -> {
                replacementBinding.set(newBinding);
                replacementRoute.set(persistedRoute);
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(
                    new SessionResolver() {
                        @Override
                        public Optional<ResolvedSession> resolveSession(String sessionId) {
                            return Optional.of(new ResolvedSession(sessionId, 42L, 7L));
                        }
                    },
                    registry,
                    routeWriter,
                    replacementHandler,
                    executor,
                    64
                ),
                downstream
            );

            channel.writeInbound(privateMessage("active:42:7", 200L, 88L));
            waitForPendingTasks(channel);

            assertEquals(new ResolvedSession("active:42:7", 42L, 7L), replacementBinding.get());
            assertNotNull(replacementRoute.get());
            assertEquals(16L, replacementRoute.get().replacedRoute().routeEpoch());
            assertEquals("old-conn", replacementRoute.get().replacedRoute().connectionId());
        }
    }

    @Test
    void asyncResolutionCancelledByChannelCloseDoesNotBindOrWriteRoute() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch resolveEntered = new CountDownLatch(1);
        CountDownLatch unblockResolve = new CountDownLatch(1);
        AtomicInteger routeWriteCount = new AtomicInteger();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionResolver sessionResolver = sessionId -> {
                resolveEntered.countDown();
                try {
                    unblockResolve.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruptedException);
                }
                return Optional.of(new ResolvedSession(sessionId, 42L, 1L));
            };
            SessionRouteWriter<Channel> routeWriter = (resolvedSession, channelRef) -> {
                routeWriteCount.incrementAndGet();
                return new PersistedSessionRoute(17L);
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(sessionResolver, registry, routeWriter, executor),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            assertTrue(resolveEntered.await(1, TimeUnit.SECONDS));

            channel.close().syncUninterruptibly();
            waitForPendingTasks(channel);

            unblockResolve.countDown();
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertEquals(0, routeWriteCount.get());
            assertEquals(0, registry.bindCount);
            assertTrue(registry.empty);
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        }
    }

    @Test
    void asyncResolutionCancelledDuringSuccessfulRouteWriteClearsPersistedRoute() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch routePersisted = new CountDownLatch(1);
        CountDownLatch unblockRouteWrite = new CountDownLatch(1);
        StagedRouteWriter routeWriter = new StagedRouteWriter(routePersisted, unblockRouteWrite, null, false);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(
                    sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 1L)),
                    registry,
                    routeWriter,
                    executor
                ),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            assertTrue(routePersisted.await(1, TimeUnit.SECONDS));

            channel.close().syncUninterruptibly();
            waitForPendingTasks(channel);

            unblockRouteWrite.countDown();
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertTrue(routeWriter.routePersisted());
            assertTrue(routeWriter.routeCleared());
            assertEquals(1, routeWriter.writeCount());
            assertEquals(1, routeWriter.clearCount());
            assertEquals(1, registry.bindCount);
            assertTrue(registry.empty);
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
        }
    }

    @Test
    void asyncResolutionCancelledAfterRouteWriteReturnsStillCleansLocalBindingWhenRouteClearFails() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch routeReturned = new CountDownLatch(1);
        StagedRouteWriter routeWriter = new StagedRouteWriter(null, null, routeReturned, true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(
                    sessionId -> Optional.of(new ResolvedSession(sessionId, 42L, 1L)),
                    registry,
                    routeWriter,
                    executor,
                    1
                ),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            assertTrue(routeReturned.await(1, TimeUnit.SECONDS));

            channel.writeInbound(receiptAck("session-42", 200L, 66L));
            waitForPendingTasks(channel);

            assertFalse(channel.isOpen());
            assertTrue(routeWriter.routePersisted());
            assertTrue(routeWriter.clearAttempted());
            assertEquals(1, routeWriter.writeCount());
            assertEquals(1, routeWriter.clearCount());
            assertEquals(1, registry.bindCount);
            assertTrue(registry.empty);
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.SESSION_VERSION_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_EPOCH_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
        }
    }

    @Test
    void asyncPendingQueueOverflowFailsFast() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        CountDownLatch resolveEntered = new CountDownLatch(1);
        CountDownLatch unblockResolve = new CountDownLatch(1);
        AtomicInteger routeWriteCount = new AtomicInteger();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionResolver sessionResolver = sessionId -> {
                resolveEntered.countDown();
                try {
                    unblockResolve.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruptedException);
                }
                return Optional.of(new ResolvedSession(sessionId, 42L, 1L));
            };
            SessionRouteWriter<Channel> routeWriter = (resolvedSession, channelRef) -> {
                routeWriteCount.incrementAndGet();
                return new PersistedSessionRoute(17L);
            };
            EmbeddedChannel channel = new EmbeddedChannel(
                new SessionBindingHandler(sessionResolver, registry, routeWriter, executor, 2),
                downstream
            );

            channel.writeInbound(privateMessage("session-42", 200L, 88L));
            assertTrue(resolveEntered.await(1, TimeUnit.SECONDS));

            channel.writeInbound(receiptAck("session-42", 200L, 66L));
            channel.writeInbound(receiptAck("session-42", 200L, 67L));

            assertEquals(0, downstream.messageCount);
            assertFalse(channel.isOpen());
            assertErrorResponse(channel, ErrorCode.INTERNAL_ERROR.code(), "session resolution backlog exceeded");
            assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
            assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());

            unblockResolve.countDown();
            waitForPendingTasks(channel);

            assertEquals(0, downstream.messageCount);
            assertEquals(0, routeWriteCount.get());
            assertEquals(0, registry.bindCount);
            assertTrue(registry.empty);
        }
    }

    private static void assertFirstInvalidSessionReturnsAuthFailure(InboundRouterHandler.InboundMessage inboundMessage) throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingInboundHandler downstream = new RecordingInboundHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new SessionBindingHandler(sessionId -> Optional.empty(), registry),
            downstream
        );

        channel.writeInbound(inboundMessage);

        assertEquals(0, downstream.messageCount);
        assertTrue(registry.empty);
        assertNull(channel.attr(SessionBindingHandler.SESSION_ID_ATTRIBUTE).get());
        assertNull(channel.attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get());
        assertSessionInvalidResponse(channel);
        assertNull(channel.readInbound());
    }

    private static void assertSessionInvalidResponse(EmbeddedChannel channel) throws Exception {
        assertErrorResponse(channel, 1000, null);
    }

    private static void assertErrorResponse(EmbeddedChannel channel, int errorCode, String expectedMessage) throws Exception {
        ByteBuf frame = channel.readOutbound();
        assertNotNull(frame);
        try {
            assertEquals(FrameConstants.HEADER_LENGTH + frame.getInt(FrameConstants.BODY_LENGTH_OFFSET), frame.readableBytes());
            assertEquals(0x4D4F4348, frame.readInt());
            assertEquals(FrameConstants.PROTOCOL_VERSION, frame.readUnsignedByte());
            assertEquals(MsgType.ERROR_RESPONSE.code(), frame.readUnsignedByte());
            assertEquals(SerializerType.PROTOBUF.code(), frame.readUnsignedByte());
            int bodyLength = frame.readInt();
            byte[] body = new byte[bodyLength];
            frame.readBytes(body);
            Mochat.ErrorResponse error = Mochat.ErrorResponse.parseFrom(body);
            assertEquals(errorCode, error.getErrorCode());
            if (expectedMessage != null) {
                assertEquals(expectedMessage, error.getMessage());
            }
        } finally {
            frame.release();
        }
        assertNull(channel.readOutbound());
    }

    private static InboundRouterHandler.InboundMessage privateMessage(String sessionId, long conversationId, long toUid) {
        return new InboundRouterHandler.InboundMessage(
            MsgType.PRIVATE_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.PrivateMessageReq.newBuilder()
                .setSessionId(sessionId)
                .setClientMsgId(1001L)
                .setConversationId(conversationId)
                .setToUid(toUid)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );
    }

    private static InboundRouterHandler.InboundMessage groupMessage(String sessionId, long conversationId, long groupId) {
        return new InboundRouterHandler.InboundMessage(
            MsgType.GROUP_MESSAGE,
            SerializerType.PROTOBUF,
            Mochat.GroupMessageReq.newBuilder()
                .setSessionId(sessionId)
                .setClientMsgId(2002L)
                .setConversationId(conversationId)
                .setGroupId(groupId)
                .setText("hello-group")
                .build()
                .toByteArray()
        );
    }

    private static InboundRouterHandler.InboundMessage receiptAck(String sessionId, long conversationId, long latestReceivedSeq) {
        return new InboundRouterHandler.InboundMessage(
            MsgType.CLIENT_RECEIVE_ACK,
            SerializerType.PROTOBUF,
            Mochat.ClientReceiveAck.newBuilder()
                .setSessionId(sessionId)
                .setConversationId(conversationId)
                .setLatestReceivedSeq(latestReceivedSeq)
                .build()
                .toByteArray()
        );
    }

    private static SessionResolver fixedResolvedSessionResolver() {
        return new SessionResolver() {
            @Override
            public Optional<ResolvedSession> resolveSession(String sessionId) {
                return Optional.of(new ResolvedSession("active:42:7", 42L, 7L));
            }
        };
    }

    private static void waitForPendingTasks(EmbeddedChannel channel) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            Thread.sleep(10L);
        }
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
    }

    private static final class RecordingInboundHandler extends ChannelInboundHandlerAdapter {
        private int messageCount;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messageCount++;
        }
    }

    private static final class RecordingPayloadHandler extends ChannelInboundHandlerAdapter {
        private final List<String> events = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            InboundRouterHandler.InboundMessage inboundMessage = (InboundRouterHandler.InboundMessage) msg;
            long conversationId;
            try {
                conversationId = switch (inboundMessage.msgType()) {
                    case PRIVATE_MESSAGE -> Mochat.PrivateMessageReq.parseFrom(inboundMessage.body()).getConversationId();
                    case CLIENT_RECEIVE_ACK -> Mochat.ClientReceiveAck.parseFrom(inboundMessage.body()).getConversationId();
                    default -> -1L;
                };
            } catch (InvalidProtocolBufferException exception) {
                throw new IllegalStateException(exception);
            }
            events.add(inboundMessage.msgType().name() + ":" + conversationId);
        }
    }

    private static final class StagedRouteWriter implements SessionRouteWriter<Channel> {
        private final CountDownLatch routePersistedSignal;
        private final CountDownLatch unblockRouteWrite;
        private final CountDownLatch routeReturnedSignal;
        private final boolean failClear;
        private final AtomicInteger writeCount = new AtomicInteger();
        private final AtomicInteger clearCount = new AtomicInteger();
        private volatile boolean routePersisted;
        private volatile boolean routeCleared;
        private volatile boolean clearAttempted;

        private StagedRouteWriter(
            CountDownLatch routePersistedSignal,
            CountDownLatch unblockRouteWrite,
            CountDownLatch routeReturnedSignal,
            boolean failClear
        ) {
            this.routePersistedSignal = routePersistedSignal;
            this.unblockRouteWrite = unblockRouteWrite;
            this.routeReturnedSignal = routeReturnedSignal;
            this.failClear = failClear;
        }

        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            writeCount.incrementAndGet();
            routePersisted = true;
            if (routePersistedSignal != null) {
                routePersistedSignal.countDown();
            }
            if (unblockRouteWrite != null) {
                try {
                    unblockRouteWrite.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruptedException);
                }
            }
            if (routeReturnedSignal != null) {
                routeReturnedSignal.countDown();
            }
            return new PersistedSessionRoute(17L);
        }

        @Override
        public boolean clearRoute(ResolvedSession resolvedSession, Channel channelRef, PersistedSessionRoute persistedRoute) {
            clearAttempted = true;
            clearCount.incrementAndGet();
            if (failClear) {
                throw new IllegalStateException("clear route failed");
            }
            routeCleared = true;
            return true;
        }

        private int writeCount() {
            return writeCount.get();
        }

        private int clearCount() {
            return clearCount.get();
        }

        private boolean routePersisted() {
            return routePersisted;
        }

        private boolean routeCleared() {
            return routeCleared;
        }

        private boolean clearAttempted() {
            return clearAttempted;
        }
    }

    private static final class RecordingRegistry implements ChannelSessionRegistry<Channel> {
        private int bindCount;
        private String sessionId;
        private long userId;
        private Channel channel;
        private boolean empty = true;

        @Override
        public void bind(ResolvedSession resolvedSession, Channel channelRef) {
            bindCount++;
            this.sessionId = resolvedSession.sessionId();
            this.userId = resolvedSession.userId();
            this.channel = channelRef;
            this.empty = false;
        }

        @Override
        public boolean unbind(String sessionId, long userId, Channel channelRef) {
            if (!this.empty && this.userId == userId && this.channel == channelRef && this.sessionId.equals(sessionId)) {
                this.sessionId = null;
                this.userId = 0L;
                this.channel = null;
                this.empty = true;
                return true;
            }
            return false;
        }
    }

    private static final class RecordingHeartbeatRouteWriter implements SessionRouteWriter<Channel> {
        private final long routeEpoch;
        private final boolean renewResult;
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicInteger clearCount = new AtomicInteger();

        private RecordingHeartbeatRouteWriter(long routeEpoch, boolean renewResult) {
            this.routeEpoch = routeEpoch;
            this.renewResult = renewResult;
        }

        @Override
        public PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, Channel channelRef) {
            return new PersistedSessionRoute(routeEpoch);
        }

        @Override
        public boolean renewRoute(ResolvedSession resolvedSession, Channel channelRef, PersistedSessionRoute persistedRoute) {
            renewCount.incrementAndGet();
            return renewResult;
        }

        @Override
        public boolean clearRoute(ResolvedSession resolvedSession, Channel channelRef, PersistedSessionRoute persistedRoute) {
            clearCount.incrementAndGet();
            return true;
        }

        private int renewCount() {
            return renewCount.get();
        }

        private int clearCount() {
            return clearCount.get();
        }
    }

    private static final class MutableSessionResolver implements SessionResolver {
        private volatile Optional<ResolvedSession> resolvedSession;

        private MutableSessionResolver(Optional<ResolvedSession> resolvedSession) {
            this.resolvedSession = resolvedSession;
        }

        @Override
        public Optional<ResolvedSession> resolveSession(String sessionId) {
            Optional<ResolvedSession> currentSession = resolvedSession;
            return currentSession.filter(session -> session.sessionId().equals(sessionId));
        }

        private void setResolvedSession(Optional<ResolvedSession> resolvedSession) {
            this.resolvedSession = resolvedSession;
        }
    }

    private static final class AsyncTestExecutor implements Executor, AutoCloseable {
        private final List<Thread> threads = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            Thread thread = new Thread(command, "async-test-executor");
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        @Override
        public void close() throws InterruptedException {
            for (Thread thread : threads) {
                thread.join(5_000L);
            }
        }
    }

    private static final class MutableDrainState implements GatewayDrainState {
        private final AtomicBoolean draining = new AtomicBoolean(false);

        @Override
        public boolean isDraining() {
            return draining.get();
        }

        private void startDrain() {
            draining.set(true);
        }
    }

    private static final class DelayedCloseHandler extends ChannelDuplexHandler {
        private final CountDownLatch closeIntercepted = new CountDownLatch(1);
        private volatile ChannelHandlerContext context;
        private volatile ChannelPromise delayedPromise;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.context = ctx;
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.delayedPromise = promise;
            closeIntercepted.countDown();
        }

        private boolean awaitCloseIntercepted() throws InterruptedException {
            return closeIntercepted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            ChannelHandlerContext localContext = context;
            ChannelPromise localPromise = delayedPromise;
            if (localContext == null || localPromise == null) {
                return;
            }
            localContext.executor().execute(() -> {
                localContext.pipeline().remove(this);
                localContext.close(localPromise);
            });
        }
    }
}
