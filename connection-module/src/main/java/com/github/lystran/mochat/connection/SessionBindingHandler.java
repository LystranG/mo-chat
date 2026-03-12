package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.PersistedSessionRoute;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolutionException;
import com.github.lystran.mochat.common.session.SessionReplacementHandler;
import com.github.lystran.mochat.common.session.SessionRouteWriteException;
import com.github.lystran.mochat.common.session.SessionRouteWriter;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

@ChannelHandler.Sharable
public final class SessionBindingHandler extends ChannelInboundHandlerAdapter {
    private static final int DEFAULT_MAX_PENDING_MESSAGES = 64;
    private static final String GATEWAY_DRAINING_MESSAGE = "gateway draining";
    public static final AttributeKey<String> SESSION_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionId");
    public static final AttributeKey<Long> USER_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.userId");
    public static final AttributeKey<Long> SESSION_VERSION_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionVersion");
    public static final AttributeKey<Long> ROUTE_EPOCH_ATTRIBUTE = AttributeKey.valueOf("mochat.routeEpoch");
    public static final AttributeKey<Boolean> ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE =
        AttributeKey.valueOf("mochat.routeOwnershipActive");
    public static final DrainGraceExpiredEvent DRAIN_GRACE_EXPIRED_EVENT = DrainGraceExpiredEvent.INSTANCE;
    private static final AttributeKey<PendingResolution> PENDING_RESOLUTION_ATTRIBUTE =
        AttributeKey.valueOf("mochat.pendingSessionResolution");
    private static final String SESSION_INVALID_MESSAGE = "session invalid";
    private static final String SESSION_RESOLUTION_UNAVAILABLE_MESSAGE = "session resolution unavailable";
    private static final String SESSION_BINDING_UNAVAILABLE_MESSAGE = "session binding unavailable";
    private static final String SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE = "session resolution backlog exceeded";
    private static final String SESSION_RESOLUTION_REJECTED_MESSAGE = "session resolution overloaded";

    private final SessionResolver sessionResolver;
    private final ChannelSessionRegistry<Channel> channelSessionRegistry;
    private final SessionRouteWriter<Channel> sessionRouteWriter;
    private final SessionReplacementHandler sessionReplacementHandler;
    private final GatewayDrainState gatewayDrainState;
    private final Executor resolutionExecutor;
    private final int maxPendingMessages;

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            SessionRouteWriter.noop(),
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            null,
            DEFAULT_MAX_PENDING_MESSAGES
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        Executor resolutionExecutor
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            SessionRouteWriter.noop(),
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            resolutionExecutor,
            DEFAULT_MAX_PENDING_MESSAGES
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            sessionRouteWriter,
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            null,
            DEFAULT_MAX_PENDING_MESSAGES
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter,
        Executor resolutionExecutor
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            sessionRouteWriter,
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            resolutionExecutor,
            DEFAULT_MAX_PENDING_MESSAGES
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        Executor resolutionExecutor,
        int maxPendingMessages
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            SessionRouteWriter.noop(),
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            resolutionExecutor,
            maxPendingMessages
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter,
        Executor resolutionExecutor,
        int maxPendingMessages
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            sessionRouteWriter,
            SessionReplacementHandler.noop(),
            GatewayDrainState.accepting(),
            resolutionExecutor,
            maxPendingMessages
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter,
        SessionReplacementHandler sessionReplacementHandler,
        Executor resolutionExecutor,
        int maxPendingMessages
    ) {
        this(
            sessionResolver,
            channelSessionRegistry,
            sessionRouteWriter,
            sessionReplacementHandler,
            GatewayDrainState.accepting(),
            resolutionExecutor,
            maxPendingMessages
        );
    }

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry,
        SessionRouteWriter<Channel> sessionRouteWriter,
        SessionReplacementHandler sessionReplacementHandler,
        GatewayDrainState gatewayDrainState,
        Executor resolutionExecutor,
        int maxPendingMessages
    ) {
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.channelSessionRegistry = Objects.requireNonNull(channelSessionRegistry, "channelSessionRegistry");
        this.sessionRouteWriter = Objects.requireNonNull(sessionRouteWriter, "sessionRouteWriter");
        this.sessionReplacementHandler = Objects.requireNonNull(sessionReplacementHandler, "sessionReplacementHandler");
        this.gatewayDrainState = Objects.requireNonNull(gatewayDrainState, "gatewayDrainState");
        this.resolutionExecutor = resolutionExecutor;
        this.maxPendingMessages = Math.max(1, maxPendingMessages);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        PendingResolution pendingResolution = ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).get();
        if (pendingResolution != null) {
            if (!enqueuePendingMessage(pendingResolution, msg)) {
                cancelPendingResolution(ctx.channel(), pendingResolution);
                failPendingResolution(ctx, pendingResolution, SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE);
            }
            return;
        }
        processMessage(ctx, msg, null, null);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clearPendingResolution(ctx.channel());
        clearBinding(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        clearPendingResolution(ctx.channel());
        clearBinding(ctx.channel());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HeartbeatHandler.HEARTBEAT_RECEIVED_EVENT) {
            handleHeartbeatReceived(ctx);
            return;
        }
        if (evt == HeartbeatHandler.HEARTBEAT_TIMEOUT_EVENT) {
            handleHeartbeatTimeout(ctx);
            return;
        }
        if (evt == DRAIN_GRACE_EXPIRED_EVENT) {
            handleDrainGraceExpired(ctx);
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    private boolean processMessage(
        ChannelHandlerContext ctx,
        Object msg,
        ArrayDeque<Object> remainingQueuedMessages,
        ResolvedSession trustedBinding
    ) {
        if (!(msg instanceof InboundRouterHandler.InboundMessage inboundMessage)) {
            ctx.fireChannelRead(msg);
            return true;
        }

        String sessionId;
        try {
            sessionId = switch (inboundMessage.msgType()) {
                case PRIVATE_MESSAGE -> Mochat.PrivateMessageReq.parseFrom(inboundMessage.body()).getSessionId();
                case GROUP_MESSAGE -> Mochat.GroupMessageReq.parseFrom(inboundMessage.body()).getSessionId();
                case CLIENT_RECEIVE_ACK -> Mochat.ClientReceiveAck.parseFrom(inboundMessage.body()).getSessionId();
                default -> null;
            };
        } catch (InvalidProtocolBufferException ignored) {
            ctx.fireChannelRead(msg);
            return true;
        }
        if (sessionId == null) {
            ctx.fireChannelRead(msg);
            return true;
        }
        if (sessionId.isBlank()) {
            clearBinding(ctx.channel());
            emitSessionInvalid(ctx);
            clearQueuedMessages(remainingQueuedMessages);
            return false;
        }

        Channel channel = ctx.channel();
        ExistingBinding existingBinding = existingBinding(channel);
        if (trustedBinding != null
            && existingBinding != null
            && sessionId.equals(trustedBinding.sessionId())
            && matchesExistingBinding(existingBinding, trustedBinding)) {
            refreshExistingBinding(channel, existingBinding, trustedBinding);
            ctx.fireChannelRead(msg);
            return true;
        }
        if (existingBinding != null && sessionId.equals(existingBinding.sessionId())) {
            if (resolutionExecutor != null) {
                beginAsyncResolution(ctx, sessionId, msg, remainingQueuedMessages, existingBinding);
                return false;
            }
            Resolution resolution = validateExistingBinding(existingBinding, resolveSession(sessionId));
            if (resolution.invalidSession()) {
                clearBinding(channel);
                emitSessionInvalid(ctx);
                clearQueuedMessages(remainingQueuedMessages);
                return false;
            }
            if (resolution.internalError()) {
                emitInternalError(ctx);
                clearQueuedMessages(remainingQueuedMessages);
                return false;
            }
            refreshExistingBinding(channel, existingBinding, resolution.binding().orElseThrow());
            ctx.fireChannelRead(msg);
            return true;
        }
        if (gatewayDrainState.isDraining()) {
            rejectNewOwnershipWhileDraining(ctx, remainingQueuedMessages);
            return false;
        }

        if (resolutionExecutor != null) {
            beginAsyncResolution(ctx, sessionId, msg, remainingQueuedMessages, null);
            return false;
        }

        Resolution resolution = resolveSession(sessionId);
        if (resolution.invalidSession()) {
            clearBinding(channel);
            emitSessionInvalid(ctx);
            clearQueuedMessages(remainingQueuedMessages);
            return false;
        }
        if (resolution.internalError()) {
            emitInternalError(ctx);
            clearQueuedMessages(remainingQueuedMessages);
            return false;
        }
        if (resolution.binding().isPresent()) {
            try {
                bind(channel, resolution.binding().orElseThrow());
            } catch (RuntimeException runtimeException) {
                emitBindFailure(ctx, runtimeException);
                clearQueuedMessages(remainingQueuedMessages);
                return false;
            }
        }
        ctx.fireChannelRead(msg);
        return true;
    }

    private void handleHeartbeatReceived(ChannelHandlerContext ctx) {
        ExistingBinding existingBinding = existingBinding(ctx.channel());
        if (!hasManagedPersistedRoute(existingBinding)) {
            return;
        }
        if (resolutionExecutor != null) {
            renewHeartbeatRouteAsync(ctx, existingBinding);
            return;
        }
        applyHeartbeatRenewalResult(ctx, existingBinding, renewHeartbeatRoute(existingBinding, ctx.channel()));
    }

    private void renewHeartbeatRouteAsync(ChannelHandlerContext ctx, ExistingBinding existingBinding) {
        try {
            resolutionExecutor.execute(() -> {
                HeartbeatRenewalResult renewalResult = renewHeartbeatRoute(existingBinding, ctx.channel());
                if (renewalResult == HeartbeatRenewalResult.AUTHORITY_UNAVAILABLE) {
                    return;
                }
                ctx.executor().execute(() -> applyHeartbeatRenewalResult(ctx, existingBinding, renewalResult));
            });
        } catch (RuntimeException ignored) {
            // Heartbeat renewals are best-effort; transient route store failures fall back to TTL expiry.
        }
    }

    private HeartbeatRenewalResult renewHeartbeatRoute(ExistingBinding existingBinding, Channel channel) {
        Resolution authorityResolution = validateExistingBinding(existingBinding, resolveSession(existingBinding.sessionId()));
        if (authorityResolution.invalidSession()) {
            return HeartbeatRenewalResult.STALE;
        }
        if (authorityResolution.internalError()) {
            return HeartbeatRenewalResult.AUTHORITY_UNAVAILABLE;
        }
        try {
            return sessionRouteWriter.renewRoute(
                existingBinding.asResolvedSession(),
                channel,
                existingBinding.asPersistedRoute()
            ) ? HeartbeatRenewalResult.RENEWED : HeartbeatRenewalResult.STALE;
        } catch (RuntimeException ignored) {
            // Heartbeat renewals are best-effort; transient route store failures fall back to TTL expiry.
            return HeartbeatRenewalResult.AUTHORITY_UNAVAILABLE;
        }
    }

    private void applyHeartbeatRenewalResult(
        ChannelHandlerContext ctx,
        ExistingBinding heartbeatBinding,
        HeartbeatRenewalResult renewalResult
    ) {
        if (renewalResult != HeartbeatRenewalResult.STALE
            || !sameManagedPersistedRoute(heartbeatBinding, existingBinding(ctx.channel()))) {
            return;
        }
        clearBinding(ctx.channel());
        ctx.close();
    }

    private void handleHeartbeatTimeout(ChannelHandlerContext ctx) {
        releaseOwnedRouteAndClose(ctx);
    }

    private void handleDrainGraceExpired(ChannelHandlerContext ctx) {
        releaseOwnedRouteAndClose(ctx);
    }

    private void releaseOwnedRouteAndClose(ChannelHandlerContext ctx) {
        ExistingBinding existingBinding = existingBinding(ctx.channel());
        clearBinding(ctx.channel());
        if (ctx.channel().isActive()) {
            ctx.close();
        }
        if (hasManagedPersistedRoute(existingBinding)) {
            clearHeartbeatRouteAsync(ctx.channel(), existingBinding);
        }
    }

    private void rejectNewOwnershipWhileDraining(
        ChannelHandlerContext ctx,
        ArrayDeque<Object> remainingQueuedMessages
    ) {
        ExistingBinding existingBinding = existingBinding(ctx.channel());
        clearBinding(ctx.channel());
        if (hasManagedPersistedRoute(existingBinding)) {
            clearHeartbeatRouteAsync(ctx.channel(), existingBinding);
        }
        emitInternalErrorAndClose(ctx, GATEWAY_DRAINING_MESSAGE);
        clearQueuedMessages(remainingQueuedMessages);
    }

    private void clearHeartbeatRouteAsync(Channel channel, ExistingBinding existingBinding) {
        Runnable clearTask = () -> tryClearPersistedRoute(
            channel,
            existingBinding.asResolvedSession(),
            existingBinding.asPersistedRoute()
        );
        try {
            if (resolutionExecutor != null) {
                resolutionExecutor.execute(clearTask);
                return;
            }
            clearTask.run();
        } catch (RuntimeException ignored) {
            clearTask.run();
        }
    }

    private Resolution resolveSession(String sessionId) {
        try {
            SessionAuthority authority = sessionResolver.resolveAuthority(sessionId);
            if (authority.status() != SessionAuthorityStatus.ACTIVE) {
                return Resolution.invalid();
            }
            return Resolution.binding(new ResolvedSession(authority.sessionId(), authority.userId(), authority.sessionVersion()));
        } catch (SessionResolutionException exception) {
            return Resolution.upstreamFailure();
        }
    }

    private void beginAsyncResolution(
        ChannelHandlerContext ctx,
        String sessionId,
        Object currentMessage,
        ArrayDeque<Object> remainingQueuedMessages,
        ExistingBinding existingBinding
    ) {
        PendingResolution pendingResolution = new PendingResolution(sessionId, existingBinding);
        if (!enqueuePendingMessage(pendingResolution, currentMessage)) {
            clearQueuedMessages(remainingQueuedMessages);
            failPendingResolution(ctx, pendingResolution, SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE);
            return;
        }
        if (remainingQueuedMessages != null) {
            while (!remainingQueuedMessages.isEmpty()) {
                Object queuedMessage = remainingQueuedMessages.pollFirst();
                if (!enqueuePendingMessage(pendingResolution, queuedMessage)) {
                    clearQueuedMessages(remainingQueuedMessages);
                    failPendingResolution(ctx, pendingResolution, SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE);
                    return;
                }
            }
        }
        ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).set(pendingResolution);
        try {
            resolutionExecutor.execute(() -> {
                AsyncResolutionResult asyncResolutionResult = resolveAndBind(ctx.channel(), sessionId, pendingResolution);
                ctx.executor().execute(() -> completeAsyncResolution(ctx, pendingResolution, asyncResolutionResult));
            });
        } catch (RuntimeException runtimeException) {
            cancelPendingResolution(ctx.channel(), pendingResolution);
            failPendingResolution(ctx, pendingResolution, SESSION_RESOLUTION_REJECTED_MESSAGE);
        }
    }

    private void completeAsyncResolution(
        ChannelHandlerContext ctx,
        PendingResolution pendingResolution,
        AsyncResolutionResult asyncResolutionResult
    ) {
        PendingResolution currentPending = ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).get();
        if (currentPending != pendingResolution) {
            cleanupAsyncBinding(ctx.channel(), asyncResolutionResult);
            return;
        }
        ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).set(null);
        if (asyncResolutionResult.cancelled()) {
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        Resolution resolution = asyncResolutionResult.resolution();
        if (resolution.invalidSession()) {
            clearBinding(ctx.channel());
            emitSessionInvalid(ctx);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        if (resolution.internalError()) {
            emitInternalError(ctx);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }

        if (asyncResolutionResult.bindFailure() != null) {
            emitBindFailure(ctx, asyncResolutionResult.bindFailure());
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        if (pendingResolution.revalidatesExistingBinding() && resolution.binding().isPresent()) {
            refreshExistingBinding(ctx.channel(), pendingResolution.existingBinding().orElseThrow(), resolution.binding().orElseThrow());
        }
        if (!pendingResolution.revalidatesExistingBinding() && resolution.binding().isPresent() && gatewayDrainState.isDraining()) {
            cleanupAsyncBinding(ctx.channel(), asyncResolutionResult);
            emitInternalErrorAndClose(ctx, GATEWAY_DRAINING_MESSAGE);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        drainQueuedMessages(ctx, pendingResolution.queuedMessages(), resolution.binding().orElse(null));
    }

    private AsyncResolutionResult resolveAndBind(Channel channel, String sessionId, PendingResolution pendingResolution) {
        if (shouldAbortAsyncBind(channel, pendingResolution)) {
            return AsyncResolutionResult.cancelledResult();
        }
        Resolution resolution = resolveSession(sessionId);
        if (pendingResolution.revalidatesExistingBinding()) {
            return AsyncResolutionResult.completed(
                validateExistingBinding(pendingResolution.existingBinding().orElseThrow(), resolution),
                null,
                null
            );
        }
        if (resolution.binding().isEmpty()) {
            return AsyncResolutionResult.completed(resolution, null, null);
        }
        if (shouldAbortAsyncBind(channel, pendingResolution)) {
            return AsyncResolutionResult.cancelledResult();
        }
        try {
            abortIfGatewayDraining();
            PersistedSessionRoute persistedRoute = bind(channel, resolution.binding().orElseThrow(), pendingResolution);
            return AsyncResolutionResult.completed(resolution, null, persistedRoute);
        } catch (RuntimeException runtimeException) {
            return AsyncResolutionResult.completed(resolution, runtimeException, null);
        }
    }

    private void drainQueuedMessages(
        ChannelHandlerContext ctx,
        ArrayDeque<Object> queuedMessages,
        ResolvedSession trustedBinding
    ) {
        while (!queuedMessages.isEmpty()) {
            Object nextMessage = queuedMessages.pollFirst();
            if (!processMessage(ctx, nextMessage, queuedMessages, trustedBinding)) {
                return;
            }
        }
    }

    private void emitSessionInvalid(ChannelHandlerContext ctx) {
        emitError(ctx, ErrorCode.SESSION_INVALID, SESSION_INVALID_MESSAGE);
    }

    private void emitInternalError(ChannelHandlerContext ctx) {
        emitError(ctx, ErrorCode.INTERNAL_ERROR, SESSION_RESOLUTION_UNAVAILABLE_MESSAGE);
    }

    private void emitInternalError(ChannelHandlerContext ctx, String message) {
        emitError(ctx, ErrorCode.INTERNAL_ERROR, message);
    }

    private Resolution validateExistingBinding(ExistingBinding existingBinding, Resolution resolution) {
        if (resolution.binding().isEmpty() || resolution.invalidSession() || resolution.internalError()) {
            return resolution;
        }
        ResolvedSession resolvedSession = resolution.binding().orElseThrow();
        return matchesExistingBinding(existingBinding, resolvedSession) ? resolution : Resolution.invalid();
    }

    private boolean matchesExistingBinding(ExistingBinding existingBinding, ResolvedSession resolvedSession) {
        return Objects.equals(existingBinding.sessionId(), resolvedSession.sessionId())
            && existingBinding.userId() == resolvedSession.userId()
            && sessionVersionsCompatible(existingBinding.sessionVersion(), resolvedSession.sessionVersion());
    }

    private boolean sessionVersionsCompatible(long currentSessionVersion, long resolvedSessionVersion) {
        return currentSessionVersion <= 0L
            || resolvedSessionVersion <= 0L
            || currentSessionVersion == resolvedSessionVersion;
    }

    private void refreshExistingBinding(Channel channel, ExistingBinding existingBinding, ResolvedSession resolvedSession) {
        if (existingBinding.sessionVersion() <= 0L && resolvedSession.sessionVersion() > 0L) {
            channel.attr(SESSION_VERSION_ATTRIBUTE).set(resolvedSession.sessionVersion());
        }
    }

    private void emitBindFailure(ChannelHandlerContext ctx, RuntimeException runtimeException) {
        if (runtimeException instanceof DrainActivatedException) {
            emitInternalErrorAndClose(ctx, GATEWAY_DRAINING_MESSAGE);
            return;
        }
        if (isOutcomeUnknown(runtimeException)) {
            emitInternalErrorAndClose(ctx, SESSION_BINDING_UNAVAILABLE_MESSAGE);
            return;
        }
        emitInternalError(ctx, SESSION_BINDING_UNAVAILABLE_MESSAGE);
    }

    private void emitInternalErrorAndClose(ChannelHandlerContext ctx, String message) {
        emitError(ctx, ErrorCode.INTERNAL_ERROR, message, true);
    }

    private void emitError(ChannelHandlerContext ctx, ErrorCode errorCode, String message) {
        emitError(ctx, errorCode, message, false);
    }

    private void emitError(ChannelHandlerContext ctx, ErrorCode errorCode, String message, boolean closeAfterWrite) {
        byte[] body = Mochat.ErrorResponse.newBuilder()
            .setErrorCode(errorCode.code())
            .setMessage(message)
            .build()
            .toByteArray();
        if (closeAfterWrite) {
            ctx.writeAndFlush(ChatChannelInitializer.encodeFrame(ctx.channel(), MsgType.ERROR_RESPONSE, body))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(ChatChannelInitializer.encodeFrame(ctx.channel(), MsgType.ERROR_RESPONSE, body));
    }

    private PersistedSessionRoute bind(Channel channel, ResolvedSession binding) {
        return bind(channel, binding, null);
    }

    private PersistedSessionRoute bind(Channel channel, ResolvedSession binding, PendingResolution pendingResolution) {
        ExistingBinding existingBinding = existingBinding(channel);
        if (existingBinding != null
            && binding.sessionId().equals(existingBinding.sessionId())
            && binding.userId() == existingBinding.userId()) {
            return PersistedSessionRoute.none();
        }

        boolean existingLocalBindingRemoved = false;
        boolean newLocalBindingEstablished = false;
        PersistedSessionRoute persistedRoute = null;
        try {
            abortIfAsyncBindCancelled(channel, pendingResolution);
            abortIfGatewayDraining();
            markRouteOwnershipPending(channel);
            if (existingBinding != null) {
                channelSessionRegistry.unbind(existingBinding.sessionId(), existingBinding.userId(), channel);
                existingLocalBindingRemoved = true;
            }

            abortIfAsyncBindCancelled(channel, pendingResolution);
            abortIfGatewayDraining();
            channelSessionRegistry.bind(binding, channel);
            newLocalBindingEstablished = true;
            abortIfAsyncBindCancelled(channel, pendingResolution);
            abortIfGatewayDraining();
            persistedRoute = sessionRouteWriter.writeRoute(binding, channel);
            abortIfAsyncBindCancelled(channel, pendingResolution);
            abortIfGatewayDraining();
            applyBindingAttributes(channel, binding, persistedRoute);
            triggerReplacement(binding, persistedRoute);
            return persistedRoute;
        } catch (RuntimeException runtimeException) {
            RuntimeException routeCleanupFailure = tryClearPersistedRoute(channel, binding, persistedRoute);
            if (newLocalBindingEstablished) {
                channelSessionRegistry.unbind(binding.sessionId(), binding.userId(), channel);
            }
            if (shouldRestoreExistingBinding(runtimeException) && existingLocalBindingRemoved && existingBinding != null) {
                channelSessionRegistry.bind(existingBinding.asResolvedSession(), channel);
            }
            if (shouldRestoreExistingBinding(runtimeException)) {
                restoreBindingAttributes(channel, existingBinding);
            } else {
                clearBindingAttributes(channel);
            }
            if (routeCleanupFailure != null) {
                runtimeException.addSuppressed(routeCleanupFailure);
            }
            throw runtimeException;
        }
    }

    private void clearBinding(Channel channel) {
        String sessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long userId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (sessionId != null && userId != null) {
            channelSessionRegistry.unbind(sessionId, userId, channel);
        }
        clearBindingAttributes(channel);
    }

    private void clearBindingAttributes(Channel channel) {
        channel.attr(SESSION_ID_ATTRIBUTE).set(null);
        channel.attr(USER_ID_ATTRIBUTE).set(null);
        channel.attr(SESSION_VERSION_ATTRIBUTE).set(null);
        channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(null);
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(null);
    }

    private ExistingBinding existingBinding(Channel channel) {
        String sessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long userId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (sessionId == null || userId == null) {
            return null;
        }
        Long sessionVersion = channel.attr(SESSION_VERSION_ATTRIBUTE).get();
        Long routeEpoch = channel.attr(ROUTE_EPOCH_ATTRIBUTE).get();
        Boolean routeOwnershipActive = channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get();
        return new ExistingBinding(
            sessionId,
            userId,
            sessionVersion == null ? 0L : sessionVersion,
            routeEpoch,
            routeOwnershipActive
        );
    }

    private boolean hasManagedPersistedRoute(ExistingBinding existingBinding) {
        return existingBinding != null
            && existingBinding.routeEpoch() != null
            && Boolean.TRUE.equals(existingBinding.routeOwnershipActive())
            && existingBinding.sessionVersion() > 0L;
    }

    private boolean sameManagedPersistedRoute(ExistingBinding left, ExistingBinding right) {
        return hasManagedPersistedRoute(left)
            && hasManagedPersistedRoute(right)
            && Objects.equals(left.sessionId(), right.sessionId())
            && left.userId() == right.userId()
            && left.sessionVersion() == right.sessionVersion()
            && Objects.equals(left.routeEpoch(), right.routeEpoch());
    }

    private void applyBindingAttributes(Channel channel, ResolvedSession binding, PersistedSessionRoute persistedRoute) {
        channel.attr(SESSION_ID_ATTRIBUTE).set(binding.sessionId());
        channel.attr(USER_ID_ATTRIBUTE).set(binding.userId());
        channel.attr(SESSION_VERSION_ATTRIBUTE).set(binding.sessionVersion());
        channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(persistedRoute.routeEpoch() > 0L ? persistedRoute.routeEpoch() : null);
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
    }

    private void restoreBindingAttributes(Channel channel, ExistingBinding existingBinding) {
        if (existingBinding == null) {
            channel.attr(SESSION_ID_ATTRIBUTE).set(null);
            channel.attr(USER_ID_ATTRIBUTE).set(null);
            channel.attr(SESSION_VERSION_ATTRIBUTE).set(null);
            channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(null);
            channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(null);
            return;
        }
        channel.attr(SESSION_ID_ATTRIBUTE).set(existingBinding.sessionId());
        channel.attr(USER_ID_ATTRIBUTE).set(existingBinding.userId());
        channel.attr(SESSION_VERSION_ATTRIBUTE).set(existingBinding.sessionVersion());
        channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(existingBinding.routeEpoch());
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(existingBinding.routeOwnershipActive());
    }

    public static boolean hasActiveRouteOwnership(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
    }

    private void triggerReplacement(ResolvedSession binding, PersistedSessionRoute persistedRoute) {
        if (persistedRoute.replacedRoute() == null) {
            return;
        }
        try {
            sessionReplacementHandler.handleReplacement(binding, persistedRoute);
        } catch (RuntimeException ignored) {
            // Replacement kick is best-effort; stale-route fencing will clean up missed closes later.
        }
    }

    private void markRouteOwnershipPending(Channel channel) {
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.FALSE);
    }

    private void cleanupAsyncBinding(Channel channel, AsyncResolutionResult asyncResolutionResult) {
        if (asyncResolutionResult.resolution().binding().isEmpty()) {
            return;
        }
        RuntimeException routeCleanupFailure = tryClearPersistedRoute(
            channel,
            asyncResolutionResult.resolution().binding().orElseThrow(),
            asyncResolutionResult.persistedRoute()
        );
        clearBinding(channel);
        if (routeCleanupFailure != null) {
            // The bind is already cancelled and local ownership is cleaned up; avoid re-failing the event loop.
            return;
        }
    }

    private RuntimeException tryClearPersistedRoute(Channel channel, ResolvedSession binding, PersistedSessionRoute persistedRoute) {
        if (persistedRoute == null) {
            return null;
        }
        try {
            sessionRouteWriter.clearRoute(binding, channel, persistedRoute);
            return null;
        } catch (RuntimeException cleanupFailure) {
            return cleanupFailure;
        }
    }

    private boolean shouldRestoreExistingBinding(RuntimeException runtimeException) {
        return !isOutcomeUnknown(runtimeException)
            && !(runtimeException instanceof AsyncBindCancelledException)
            && !(runtimeException instanceof DrainActivatedException);
    }

    private boolean isOutcomeUnknown(RuntimeException runtimeException) {
        return runtimeException instanceof SessionRouteWriteException sessionRouteWriteException
            && sessionRouteWriteException.outcomeUnknown();
    }

    private boolean shouldAbortAsyncBind(Channel channel, PendingResolution pendingResolution) {
        return pendingResolution != null && (pendingResolution.cancelled() || !channel.isActive() || !channel.isOpen());
    }

    private void abortIfAsyncBindCancelled(Channel channel, PendingResolution pendingResolution) {
        if (shouldAbortAsyncBind(channel, pendingResolution)) {
            throw AsyncBindCancelledException.INSTANCE;
        }
    }

    private void abortIfGatewayDraining() {
        if (gatewayDrainState.isDraining()) {
            throw DrainActivatedException.INSTANCE;
        }
    }

    private void clearPendingResolution(Channel channel) {
        PendingResolution pendingResolution = channel.attr(PENDING_RESOLUTION_ATTRIBUTE).getAndSet(null);
        if (pendingResolution != null) {
            pendingResolution.cancel();
            clearQueuedMessages(pendingResolution.queuedMessages());
        }
    }

    private void cancelPendingResolution(Channel channel, PendingResolution pendingResolution) {
        channel.attr(PENDING_RESOLUTION_ATTRIBUTE).compareAndSet(pendingResolution, null);
        pendingResolution.cancel();
    }

    private void clearQueuedMessages(ArrayDeque<Object> queuedMessages) {
        if (queuedMessages == null) {
            return;
        }
        queuedMessages.clear();
    }

    private boolean enqueuePendingMessage(PendingResolution pendingResolution, Object msg) {
        if (pendingResolution.queuedMessages().size() >= maxPendingMessages) {
            return false;
        }
        pendingResolution.queuedMessages().addLast(msg);
        return true;
    }

    private void failPendingResolution(ChannelHandlerContext ctx, PendingResolution pendingResolution, String message) {
        clearQueuedMessages(pendingResolution.queuedMessages());
        clearBinding(ctx.channel());
        emitInternalErrorAndClose(ctx, message);
    }

    private static final class PendingResolution {
        private final String sessionId;
        private final ExistingBinding existingBinding;
        private final ArrayDeque<Object> queuedMessages = new ArrayDeque<>();
        private volatile boolean cancelled;

        private PendingResolution(String sessionId, ExistingBinding existingBinding) {
            this.sessionId = sessionId;
            this.existingBinding = existingBinding;
        }

        private String sessionId() {
            return sessionId;
        }

        private Optional<ExistingBinding> existingBinding() {
            return Optional.ofNullable(existingBinding);
        }

        private ArrayDeque<Object> queuedMessages() {
            return queuedMessages;
        }

        private boolean revalidatesExistingBinding() {
            return existingBinding != null;
        }

        private void cancel() {
            this.cancelled = true;
        }

        private boolean cancelled() {
            return cancelled;
        }
    }

    private static final class AsyncBindCancelledException extends RuntimeException {
        private static final AsyncBindCancelledException INSTANCE = new AsyncBindCancelledException();

        private AsyncBindCancelledException() {
            super("async bind cancelled");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class DrainActivatedException extends RuntimeException {
        private static final DrainActivatedException INSTANCE = new DrainActivatedException();

        private DrainActivatedException() {
            super(GATEWAY_DRAINING_MESSAGE);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    public static final class DrainGraceExpiredEvent {
        private static final DrainGraceExpiredEvent INSTANCE = new DrainGraceExpiredEvent();

        private DrainGraceExpiredEvent() {
        }
    }

    private record ExistingBinding(
        String sessionId,
        long userId,
        long sessionVersion,
        Long routeEpoch,
        Boolean routeOwnershipActive
    ) {
        private ResolvedSession asResolvedSession() {
            return new ResolvedSession(sessionId, userId, sessionVersion);
        }

        private PersistedSessionRoute asPersistedRoute() {
            return routeEpoch == null ? PersistedSessionRoute.none() : new PersistedSessionRoute(routeEpoch);
        }
    }

    private record AsyncResolutionResult(
        Resolution resolution,
        RuntimeException bindFailure,
        PersistedSessionRoute persistedRoute,
        boolean cancelled
    ) {
        private AsyncResolutionResult {
            Objects.requireNonNull(resolution, "resolution");
        }

        private static AsyncResolutionResult cancelledResult() {
            return new AsyncResolutionResult(Resolution.notApplicable(), null, null, true);
        }

        private static AsyncResolutionResult completed(
            Resolution resolution,
            RuntimeException bindFailure,
            PersistedSessionRoute persistedRoute
        ) {
            return new AsyncResolutionResult(resolution, bindFailure, persistedRoute, false);
        }
    }

    private record Resolution(Optional<ResolvedSession> binding, boolean invalidSession, boolean internalError) {
        private Resolution {
            Objects.requireNonNull(binding, "binding");
        }

        private static Resolution notApplicable() {
            return new Resolution(Optional.empty(), false, false);
        }

        private static Resolution invalid() {
            return new Resolution(Optional.empty(), true, false);
        }

        private static Resolution upstreamFailure() {
            return new Resolution(Optional.empty(), false, true);
        }

        private static Resolution binding(ResolvedSession binding) {
            return new Resolution(Optional.of(binding), false, false);
        }
    }

    private enum HeartbeatRenewalResult {
        RENEWED,
        STALE,
        AUTHORITY_UNAVAILABLE
    }
}
