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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

@ChannelHandler.Sharable
/**
 * 负责把一条 TCP 连接和用户会话绑在一起，并维护在线路由、心跳续租和退场清理。
 */
public final class SessionBindingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SessionBindingHandler.class);
    private static final int DEFAULT_MAX_PENDING_MESSAGES = 64;
    private static final String GATEWAY_DRAINING_MESSAGE = "gateway draining";
    /**
     * 连接上当前绑定的会话 ID。
     */
    public static final AttributeKey<String> SESSION_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionId");
    /**
     * 连接上当前绑定的用户 ID。
     */
    public static final AttributeKey<Long> USER_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.userId");
    /**
     * 连接上当前绑定的会话版本，用来识别是不是旧登录状态。
     */
    public static final AttributeKey<Long> SESSION_VERSION_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionVersion");
    /**
     * 连接当前占用的在线路由版本，用来识别是不是已经被新连接顶掉。
     */
    public static final AttributeKey<Long> ROUTE_EPOCH_ATTRIBUTE = AttributeKey.valueOf("mochat.routeEpoch");
    /**
     * 这条连接现在是不是还真正拥有在线路由。
     */
    public static final AttributeKey<Boolean> ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE =
        AttributeKey.valueOf("mochat.routeOwnershipActive");
    /**
     * drain 宽限期结束时发给连接的内部事件。
     */
    public static final DrainGraceExpiredEvent DRAIN_GRACE_EXPIRED_EVENT = DrainGraceExpiredEvent.INSTANCE;
    /**
     * 连接上挂着的“正在异步认人”状态，里面会暂存期间收到的消息。
     */
    private static final AttributeKey<PendingResolution> PENDING_RESOLUTION_ATTRIBUTE =
        AttributeKey.valueOf("mochat.pendingSessionResolution");
    private static final String SESSION_INVALID_MESSAGE = "session invalid";
    private static final String SESSION_RESOLUTION_UNAVAILABLE_MESSAGE = "session resolution unavailable";
    private static final String SESSION_BINDING_UNAVAILABLE_MESSAGE = "session binding unavailable";
    private static final String SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE = "session resolution backlog exceeded";
    private static final String SESSION_RESOLUTION_REJECTED_MESSAGE = "session resolution overloaded";

    /**
     * 用来向权威服务确认这条 session 现在还是否有效。
     */
    private final SessionResolver sessionResolver;
    /**
     * 本地会话绑定目录，负责记录“这条连接已经认成哪个用户”。
     */
    private final ChannelSessionRegistry<Channel> channelSessionRegistry;
    /**
     * 在线路由写入器，负责把“这个网关正在负责这个用户连接”写入外部存储。
     */
    private final SessionRouteWriter<Channel> sessionRouteWriter;
    /**
     * 当新连接顶掉旧连接时，用它去通知旧连接退场。
     */
    private final SessionReplacementHandler sessionReplacementHandler;
    /**
     * 网关是否已经进入 drain，只退不进。
     */
    private final GatewayDrainState gatewayDrainState;
    /**
     * 异步做会话校验的执行器；为空时就在当前线程直接做。
     */
    private final Executor resolutionExecutor;
    /**
     * 异步校验期间最多积压多少条消息，防止一条连接无限堆消息。
     */
    private final int maxPendingMessages;

    /**
     * 使用默认的路由写入和替换策略创建绑定处理器。
     */
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

    /**
     * 使用异步执行器创建绑定处理器。
     */
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

    /**
     * 指定在线路由写入器，其他策略走默认值。
     */
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

    /**
     * 指定路由写入器和异步执行器。
     */
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

    /**
     * 指定异步执行器和消息积压上限。
     */
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

    /**
     * 指定路由写入器、异步执行器和消息积压上限。
     */
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

    /**
     * 指定旧连接替换策略。
     */
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

    /**
     * 完整构造器，允许自定义路由写入、旧连接替换、drain 判断和异步执行策略。
     */
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
    /**
     * 如果连接正在异步认人，就先把消息排队；否则直接按消息内容进入绑定或业务流程。
     */
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        PendingResolution pendingResolution = ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).get();
        if (pendingResolution != null) {
            if (!enqueuePendingMessage(pendingResolution, msg)) {
                log.info("连接 {} 异步认人期间消息积压超过上限，取消本次认人", ctx.channel().id().asShortText());
                cancelPendingResolution(ctx.channel(), pendingResolution);
                failPendingResolution(ctx, pendingResolution, SESSION_RESOLUTION_BACKLOG_EXCEEDED_MESSAGE);
            }
            return;
        }
        processMessage(ctx, msg, null, null);
    }

    @Override
    /**
     * 连接断开时清掉挂起的异步绑定和本地绑定状态。
     */
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("连接 {} 断开，清理绑定和挂起状态", ctx.channel().id().asShortText());
        clearPendingResolution(ctx.channel());
        clearBinding(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    /**
     * 处理器被移除时同样要清理挂起状态和绑定状态。
     */
    public void handlerRemoved(ChannelHandlerContext ctx) {
        log.info("连接 {} 处理器移除，清理绑定和挂起状态", ctx.channel().id().asShortText());
        clearPendingResolution(ctx.channel());
        clearBinding(ctx.channel());
    }

    @Override
    /**
     * 接收心跳、心跳超时和 drain 到期这三类内部事件。
     */
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == HeartbeatHandler.HEARTBEAT_RECEIVED_EVENT) {
            log.debug("连接 {} 收到客户端心跳", ctx.channel().id().asShortText());
            handleHeartbeatReceived(ctx);
            return;
        }
        if (evt == HeartbeatHandler.HEARTBEAT_TIMEOUT_EVENT) {
            log.info("连接 {} 心跳超时，准备关闭", ctx.channel().id().asShortText());
            handleHeartbeatTimeout(ctx);
            return;
        }
        if (evt == DRAIN_GRACE_EXPIRED_EVENT) {
            log.info("连接 {} drain 宽限期到期，关闭连接", ctx.channel().id().asShortText());
            handleDrainGraceExpired(ctx);
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * 从消息里取出 sessionId，并决定是复用旧绑定、重新认人，还是拒绝这条消息。
     */
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
            // 这批排队消息已经对应同一份刚验证过的绑定，直接放行即可。
            refreshExistingBinding(channel, existingBinding, trustedBinding);
            ctx.fireChannelRead(msg);
            return true;
        }
        if (existingBinding != null && sessionId.equals(existingBinding.sessionId())) {
            // 已有绑定且 sessionId 没变，直接放行，不需要每次都重新鉴权
            ctx.fireChannelRead(msg);
            return true;
        }
        if (gatewayDrainState.isDraining()) {
            // drain 期间不再接收新的连接绑定，客户端需要重连到别的网关。
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

    /**
     * 收到客户端心跳后，如果这条连接还持有在线路由，就顺手做一次续租。
     */
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

    /**
     * 异步续租在线路由，避免阻塞 Netty 事件循环。
     */
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
            // 心跳续租只是尽力而为；如果路由存储短暂出问题，就先靠原来的过期时间兜底。
        }
    }

    /**
     * 先重新确认 session 还是有效的，再尝试续租当前在线路由。
     */
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
            // 心跳续租只是尽力而为；如果路由存储短暂出问题，就先靠原来的过期时间兜底。
            return HeartbeatRenewalResult.AUTHORITY_UNAVAILABLE;
        }
    }

    /**
     * 如果续租结果说明自己已经不是当前路由的持有者，就主动关闭这条旧连接。
     */
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

    /**
     * 心跳超时后，把当前网关对这条连接的负责关系清掉并关闭连接。
     */
    private void handleHeartbeatTimeout(ChannelHandlerContext ctx) {
        releaseOwnedRouteAndClose(ctx);
    }

    /**
     * drain 宽限期结束后，把当前网关对这条连接的负责关系清掉并关闭连接。
     */
    private void handleDrainGraceExpired(ChannelHandlerContext ctx) {
        releaseOwnedRouteAndClose(ctx);
    }

    /**
     * 先清掉本地绑定，再异步尝试删除外部在线路由，最后关闭连接。
     */
    private void releaseOwnedRouteAndClose(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ExistingBinding existingBinding = existingBinding(channel);
        log.info("连接 [{}] 释放路由并关闭, userId={}, sessionId={}", channel.id().asShortText(),
            existingBinding != null ? existingBinding.userId() : "无",
            existingBinding != null ? existingBinding.sessionId() : "无");
        clearBinding(channel);
        if (channel.isActive()) {
            ctx.close();
        }
        if (hasManagedPersistedRoute(existingBinding)) {
            clearHeartbeatRouteAsync(channel, existingBinding);
        }
    }

    /**
     * 网关已经开始退场时，拒绝让当前网关接手这条新连接，并让客户端尽快去别的网关重连。
     */
    private void rejectNewOwnershipWhileDraining(
        ChannelHandlerContext ctx,
        ArrayDeque<Object> remainingQueuedMessages
    ) {
        log.info("连接 {} 被拒绝绑定，网关正在退场(drain)中", ctx.channel().id().asShortText());
        ExistingBinding existingBinding = existingBinding(ctx.channel());
        clearBinding(ctx.channel());
        if (hasManagedPersistedRoute(existingBinding)) {
            clearHeartbeatRouteAsync(ctx.channel(), existingBinding);
        }
        emitInternalErrorAndClose(ctx, GATEWAY_DRAINING_MESSAGE);
        clearQueuedMessages(remainingQueuedMessages);
    }

    /**
     * 异步清掉在线路由，避免连接关闭过程被外部存储拖慢。
     */
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

    /**
     * 向会话权威查询 session 是否有效，并转换成绑定流程可消费的结果。
     */
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

    /**
     * 把当前消息和后续排队消息挂到异步会话解析流程上，等认人完成后再继续处理。
     */
    private void beginAsyncResolution(
        ChannelHandlerContext ctx,
        String sessionId,
        Object currentMessage,
        ArrayDeque<Object> remainingQueuedMessages,
        ExistingBinding existingBinding
    ) {
        PendingResolution pendingResolution = new PendingResolution(sessionId, existingBinding);
        log.info("连接 [{}] 开始异步认人: sessionId={}", ctx.channel().id().asShortText(), sessionId);
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

    /**
     * 把异步认人的结果重新切回事件循环线程，并继续处理积压的消息。
     */
    private void completeAsyncResolution(
        ChannelHandlerContext ctx,
        PendingResolution pendingResolution,
        AsyncResolutionResult asyncResolutionResult
    ) {
        PendingResolution currentPending = ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).get();
        if (currentPending != pendingResolution) {
            log.info("连接 [{}] 异步认人结果已过期（被新认人覆盖）", ctx.channel().id().asShortText());
            cleanupAsyncBinding(ctx.channel(), asyncResolutionResult);
            return;
        }
        ctx.channel().attr(PENDING_RESOLUTION_ATTRIBUTE).set(null);
        if (asyncResolutionResult.cancelled()) {
            log.info("连接 [{}] 异步认人被取消", ctx.channel().id().asShortText());
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        Resolution resolution = asyncResolutionResult.resolution();
        if (resolution.invalidSession()) {
            log.info("连接 [{}] 异步认人结果: 会话无效", ctx.channel().id().asShortText());
            clearBinding(ctx.channel());
            emitSessionInvalid(ctx);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        if (resolution.internalError()) {
            log.info("连接 [{}] 异步认人结果: 上游服务不可用", ctx.channel().id().asShortText());
            emitInternalError(ctx);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }

        if (asyncResolutionResult.bindFailure() != null) {
            log.info("连接 [{}] 异步认人结果: 绑定失败", ctx.channel().id().asShortText());
            emitBindFailure(ctx, asyncResolutionResult.bindFailure());
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        if (pendingResolution.revalidatesExistingBinding() && resolution.binding().isPresent()) {
            refreshExistingBinding(ctx.channel(), pendingResolution.existingBinding().orElseThrow(), resolution.binding().orElseThrow());
        }
        if (!pendingResolution.revalidatesExistingBinding() && resolution.binding().isPresent() && gatewayDrainState.isDraining()) {
            log.info("连接 [{}] 异步认人完成但网关已在退场，拒绝绑定", ctx.channel().id().asShortText());
            cleanupAsyncBinding(ctx.channel(), asyncResolutionResult);
            emitInternalErrorAndClose(ctx, GATEWAY_DRAINING_MESSAGE);
            clearQueuedMessages(pendingResolution.queuedMessages());
            return;
        }
        log.info("连接 [{}] 异步认人完成, 开始处理排队消息", ctx.channel().id().asShortText());
        drainQueuedMessages(ctx, pendingResolution.queuedMessages(), resolution.binding().orElse(null));
    }

    /**
     * 在线程池里完成会话校验和首次绑定，避免阻塞网络线程。
     */
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

    /**
     * 认人完成后，把等待中的消息按原顺序继续送回处理链。
     */
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

    /**
     * 返回“session 无效”的客户端错误。
     */
    private void emitSessionInvalid(ChannelHandlerContext ctx) {
        emitError(ctx, ErrorCode.SESSION_INVALID, SESSION_INVALID_MESSAGE);
    }

    /**
     * 返回通用内部错误，表示当前无法完成会话校验。
     */
    private void emitInternalError(ChannelHandlerContext ctx) {
        emitError(ctx, ErrorCode.INTERNAL_ERROR, SESSION_RESOLUTION_UNAVAILABLE_MESSAGE);
    }

    /**
     * 返回带自定义提示语的内部错误。
     */
    private void emitInternalError(ChannelHandlerContext ctx, String message) {
        emitError(ctx, ErrorCode.INTERNAL_ERROR, message);
    }

    /**
     * 用权威查询结果重新核对当前已有绑定，确认这条连接还没过期。
     */
    private Resolution validateExistingBinding(ExistingBinding existingBinding, Resolution resolution) {
        if (resolution.binding().isEmpty() || resolution.invalidSession() || resolution.internalError()) {
            return resolution;
        }
        ResolvedSession resolvedSession = resolution.binding().orElseThrow();
        return matchesExistingBinding(existingBinding, resolvedSession) ? resolution : Resolution.invalid();
    }

    /**
     * 判断当前已有绑定和最新会话结果是不是同一个用户、同一个 session。
     */
    private boolean matchesExistingBinding(ExistingBinding existingBinding, ResolvedSession resolvedSession) {
        return Objects.equals(existingBinding.sessionId(), resolvedSession.sessionId())
            && existingBinding.userId() == resolvedSession.userId()
            && sessionVersionsCompatible(existingBinding.sessionVersion(), resolvedSession.sessionVersion());
    }

    /**
     * 在会话版本未知时允许继续沿用，否则要求版本完全一致。
     */
    private boolean sessionVersionsCompatible(long currentSessionVersion, long resolvedSessionVersion) {
        return currentSessionVersion <= 0L
            || resolvedSessionVersion <= 0L
            || currentSessionVersion == resolvedSessionVersion;
    }

    /**
     * 当旧绑定缺少会话版本而新结果带回版本时，补齐本地属性。
     */
    private void refreshExistingBinding(Channel channel, ExistingBinding existingBinding, ResolvedSession resolvedSession) {
        if (existingBinding.sessionVersion() <= 0L && resolvedSession.sessionVersion() > 0L) {
            channel.attr(SESSION_VERSION_ATTRIBUTE).set(resolvedSession.sessionVersion());
        }
    }

    /**
     * 根据绑定失败原因，决定回普通错误还是直接关连接。
     */
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

    /**
     * 返回错误后立刻关闭连接。
     */
    private void emitInternalErrorAndClose(ChannelHandlerContext ctx, String message) {
        log.warn("连接 [{}] 返回内部错误并关闭: {}", ctx.channel().id().asShortText(), message);
        emitError(ctx, ErrorCode.INTERNAL_ERROR, message, true);
    }

    /**
     * 返回错误，但不主动关闭连接。
     */
    private void emitError(ChannelHandlerContext ctx, ErrorCode errorCode, String message) {
        emitError(ctx, errorCode, message, false);
    }

    /**
     * 把错误编码成协议帧写回客户端，必要时在发送后关闭连接。
     */
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

    /**
     * 同步绑定入口，给不走异步排队的路径使用。
     */
    private PersistedSessionRoute bind(Channel channel, ResolvedSession binding) {
        return bind(channel, binding, null);
    }

    /**
     * 完整执行绑定流程：解绑旧状态、建立本地绑定、写在线路由、再通知旧连接退场。
     */
    private PersistedSessionRoute bind(Channel channel, ResolvedSession binding, PendingResolution pendingResolution) {
        ExistingBinding existingBinding = existingBinding(channel);
        if (existingBinding != null
            && binding.sessionId().equals(existingBinding.sessionId())
            && binding.userId() == existingBinding.userId()) {
            return PersistedSessionRoute.none();
        }

        log.info("连接 [{}] 开始绑定用户 [{}], sessionId={}", channel.id().asShortText(), binding.userId(), binding.sessionId());
        boolean existingLocalBindingRemoved = false;
        boolean newLocalBindingEstablished = false;
        PersistedSessionRoute persistedRoute = null;
        try {
            abortIfAsyncBindCancelled(channel, pendingResolution);
            abortIfGatewayDraining();
            markRouteOwnershipPending(channel);
            if (existingBinding != null) {
                log.info("连接 [{}] 解绑旧会话 sessionId={}, userId={}", channel.id().asShortText(), existingBinding.sessionId(), existingBinding.userId());
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
            log.info("连接 [{}] 绑定用户 [{}] 成功, routeEpoch={}", channel.id().asShortText(), binding.userId(), persistedRoute != null ? persistedRoute.routeEpoch() : "none");
            return persistedRoute;
        } catch (RuntimeException runtimeException) {
            log.warn("连接 [{}] 绑定用户 [{}] 失败: {}", channel.id().asShortText(), binding.userId(), runtimeException.getMessage());
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

    /**
     * 清掉本地绑定目录和连接属性。
     */
    private void clearBinding(Channel channel) {
        String sessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long userId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (sessionId != null && userId != null) {
            log.info("连接 [{}] 清理绑定: userId={}, sessionId={}", channel.id().asShortText(), userId, sessionId);
            channelSessionRegistry.unbind(sessionId, userId, channel);
        }
        clearBindingAttributes(channel);
    }

    /**
     * 清空连接上记录的绑定属性。
     */
    private void clearBindingAttributes(Channel channel) {
        channel.attr(SESSION_ID_ATTRIBUTE).set(null);
        channel.attr(USER_ID_ATTRIBUTE).set(null);
        channel.attr(SESSION_VERSION_ATTRIBUTE).set(null);
        channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(null);
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(null);
    }

    /**
     * 从连接属性里读取当前已有绑定。
     */
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

    /**
     * 判断这条连接是否同时具备会话版本、路由版本和“仍由当前网关负责”的标记。
     */
    private boolean hasManagedPersistedRoute(ExistingBinding existingBinding) {
        return existingBinding != null
            && existingBinding.routeEpoch() != null
            && Boolean.TRUE.equals(existingBinding.routeOwnershipActive())
            && existingBinding.sessionVersion() > 0L;
    }

    /**
     * 判断两个绑定是否指向同一条仍然有效的在线路由。
     */
    private boolean sameManagedPersistedRoute(ExistingBinding left, ExistingBinding right) {
        return hasManagedPersistedRoute(left)
            && hasManagedPersistedRoute(right)
            && Objects.equals(left.sessionId(), right.sessionId())
            && left.userId() == right.userId()
            && left.sessionVersion() == right.sessionVersion()
            && Objects.equals(left.routeEpoch(), right.routeEpoch());
    }

    /**
     * 把绑定结果写回连接属性，标记这条连接已经正式由当前网关负责。
     */
    private void applyBindingAttributes(Channel channel, ResolvedSession binding, PersistedSessionRoute persistedRoute) {
        channel.attr(SESSION_ID_ATTRIBUTE).set(binding.sessionId());
        channel.attr(USER_ID_ATTRIBUTE).set(binding.userId());
        channel.attr(SESSION_VERSION_ATTRIBUTE).set(binding.sessionVersion());
        channel.attr(ROUTE_EPOCH_ATTRIBUTE).set(persistedRoute.routeEpoch() > 0L ? persistedRoute.routeEpoch() : null);
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.TRUE);
    }

    /**
     * 绑定失败需要回滚时，尽量把连接属性恢复到失败前的样子。
     */
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

    /**
     * 判断这条连接现在是不是还被认为是真正的在线路由持有者。
     */
    public static boolean hasActiveRouteOwnership(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).get());
    }

    /**
     * 如果这次写路由顺手带出了旧路由，就通知旧连接退场。
     */
    private void triggerReplacement(ResolvedSession binding, PersistedSessionRoute persistedRoute) {
        if (persistedRoute.replacedRoute() == null) {
            return;
        }
        try {
            sessionReplacementHandler.handleReplacement(binding, persistedRoute);
        } catch (RuntimeException ignored) {
            // 通知旧连接退场只是尽力而为；就算这次没通知到，后面的旧路由校验也会把脏状态清掉。
        }
    }

    /**
     * 在绑定尚未完全成功前，先把“当前网关正在负责这条连接”的标记设成 false。
     */
    private void markRouteOwnershipPending(Channel channel) {
        channel.attr(ROUTE_OWNERSHIP_ACTIVE_ATTRIBUTE).set(Boolean.FALSE);
    }

    /**
     * 异步绑定结果已经没人接收时，兜底清掉刚写下的路由和本地绑定。
     */
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
            // 这次绑定已经取消，本地状态也收干净了，这里不要再把事件循环打成失败。
            return;
        }
    }

    /**
     * 尝试把刚写下的在线路由删掉；如果删失败，把异常交给上层决定怎么处理。
     */
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

    /**
     * 某些失败可以安全回滚到旧绑定，某些则必须直接清掉，避免留下真假难辨的状态。
     */
    private boolean shouldRestoreExistingBinding(RuntimeException runtimeException) {
        return !isOutcomeUnknown(runtimeException)
            && !(runtimeException instanceof AsyncBindCancelledException)
            && !(runtimeException instanceof DrainActivatedException);
    }

    /**
     * 判断“写路由结果未知”这种最危险的失败类型。
     */
    private boolean isOutcomeUnknown(RuntimeException runtimeException) {
        return runtimeException instanceof SessionRouteWriteException sessionRouteWriteException
            && sessionRouteWriteException.outcomeUnknown();
    }

    /**
     * 判断异步绑定是否应该提前放弃，比如连接已经断开或任务已取消。
     */
    private boolean shouldAbortAsyncBind(Channel channel, PendingResolution pendingResolution) {
        return pendingResolution != null && (pendingResolution.cancelled() || !channel.isActive() || !channel.isOpen());
    }

    /**
     * 如果异步绑定已经没必要继续，就抛出轻量异常尽快结束流程。
     */
    private void abortIfAsyncBindCancelled(Channel channel, PendingResolution pendingResolution) {
        if (shouldAbortAsyncBind(channel, pendingResolution)) {
            throw AsyncBindCancelledException.INSTANCE;
        }
    }

    /**
     * 网关进入 drain 后，阻止新的归属继续落地。
     */
    private void abortIfGatewayDraining() {
        if (gatewayDrainState.isDraining()) {
            throw DrainActivatedException.INSTANCE;
        }
    }

    /**
     * 清理连接上仍挂着的异步认人状态，并丢弃排队消息。
     */
    private void clearPendingResolution(Channel channel) {
        PendingResolution pendingResolution = channel.attr(PENDING_RESOLUTION_ATTRIBUTE).getAndSet(null);
        if (pendingResolution != null) {
            pendingResolution.cancel();
            clearQueuedMessages(pendingResolution.queuedMessages());
        }
    }

    /**
     * 只取消当前这次异步认人，不额外动别的状态。
     */
    private void cancelPendingResolution(Channel channel, PendingResolution pendingResolution) {
        channel.attr(PENDING_RESOLUTION_ATTRIBUTE).compareAndSet(pendingResolution, null);
        pendingResolution.cancel();
    }

    /**
     * 清空排队中的消息引用。
     */
    private void clearQueuedMessages(ArrayDeque<Object> queuedMessages) {
        if (queuedMessages == null) {
            return;
        }
        queuedMessages.clear();
    }

    /**
     * 把消息塞进等待异步认人的队列，超过上限就拒绝。
     */
    private boolean enqueuePendingMessage(PendingResolution pendingResolution, Object msg) {
        if (pendingResolution.queuedMessages().size() >= maxPendingMessages) {
            return false;
        }
        pendingResolution.queuedMessages().addLast(msg);
        return true;
    }

    /**
     * 异步认人无法继续时，清掉状态并返回错误给客户端。
     */
    private void failPendingResolution(ChannelHandlerContext ctx, PendingResolution pendingResolution, String message) {
        clearQueuedMessages(pendingResolution.queuedMessages());
        clearBinding(ctx.channel());
        emitInternalErrorAndClose(ctx, message);
    }

    /**
     * 一次正在进行中的异步会话解析任务。
     */
    private static final class PendingResolution {
        private final String sessionId;
        private final ExistingBinding existingBinding;
        private final ArrayDeque<Object> queuedMessages = new ArrayDeque<>();
        private volatile boolean cancelled;

        /**
         * 记录这次异步解析针对哪个 session，以及它是不是在复核旧绑定。
         */
        private PendingResolution(String sessionId, ExistingBinding existingBinding) {
            this.sessionId = sessionId;
            this.existingBinding = existingBinding;
        }

        /**
         * 返回这次异步解析对应的 sessionId。
         */
        private String sessionId() {
            return sessionId;
        }

        /**
         * 返回需要复核的旧绑定；为空表示这是一次全新绑定。
         */
        private Optional<ExistingBinding> existingBinding() {
            return Optional.ofNullable(existingBinding);
        }

        /**
         * 返回异步解析期间暂存的消息队列。
         */
        private ArrayDeque<Object> queuedMessages() {
            return queuedMessages;
        }

        /**
         * 判断这次异步解析是不是在确认旧绑定仍然有效。
         */
        private boolean revalidatesExistingBinding() {
            return existingBinding != null;
        }

        /**
         * 标记这次异步解析已经不需要继续了。
         */
        private void cancel() {
            this.cancelled = true;
        }

        /**
         * 返回这次异步解析是否已被取消。
         */
        private boolean cancelled() {
            return cancelled;
        }
    }

    /**
     * 表示异步绑定已经失去继续执行的意义。
     */
    private static final class AsyncBindCancelledException extends RuntimeException {
        private static final AsyncBindCancelledException INSTANCE = new AsyncBindCancelledException();

        /**
         * 单例异常，避免重复创建对象。
         */
        private AsyncBindCancelledException() {
            super("async bind cancelled");
        }

        @Override
        /**
         * 这类流程控制异常不需要堆栈，减少开销。
         */
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * 表示网关已进入 drain，新的绑定不能再继续。
     */
    private static final class DrainActivatedException extends RuntimeException {
        private static final DrainActivatedException INSTANCE = new DrainActivatedException();

        /**
         * 单例异常，避免重复创建对象。
         */
        private DrainActivatedException() {
            super(GATEWAY_DRAINING_MESSAGE);
        }

        @Override
        /**
         * 这类流程控制异常不需要堆栈，减少开销。
         */
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * 表示 drain 宽限期已经结束，连接该主动退场了。
     */
    public static final class DrainGraceExpiredEvent {
        private static final DrainGraceExpiredEvent INSTANCE = new DrainGraceExpiredEvent();

        /**
         * 单例事件，不允许外部创建。
         */
        private DrainGraceExpiredEvent() {
        }
    }

    /**
     * 从连接属性里整理出的当前绑定快照。
     *
     * @param sessionId 当前绑定的 sessionId
     * @param userId 当前绑定的用户 ID
     * @param sessionVersion 当前会话版本
     * @param routeEpoch 当前路由版本
     * @param routeOwnershipActive 这条连接现在是不是还持有在线路由
     */
    private record ExistingBinding(
        String sessionId,
        long userId,
        long sessionVersion,
        Long routeEpoch,
        Boolean routeOwnershipActive
    ) {
        /**
         * 把绑定快照转回标准的已解析会话对象。
         */
        private ResolvedSession asResolvedSession() {
            return new ResolvedSession(sessionId, userId, sessionVersion);
        }

        /**
         * 把绑定快照转回可用于清理和续租的路由对象。
         */
        private PersistedSessionRoute asPersistedRoute() {
            return routeEpoch == null ? PersistedSessionRoute.none() : new PersistedSessionRoute(routeEpoch);
        }
    }

    /**
     * 一次异步认人完成后的结果。
     *
     * @param resolution 会话解析结果
     * @param bindFailure 绑定阶段抛出的异常
     * @param persistedRoute 这次成功写下的在线路由
     * @param cancelled 这次结果是否因为取消而无效
     */
    private record AsyncResolutionResult(
        Resolution resolution,
        RuntimeException bindFailure,
        PersistedSessionRoute persistedRoute,
        boolean cancelled
    ) {
        /**
         * 确保解析结果本身不为空。
         */
        private AsyncResolutionResult {
            Objects.requireNonNull(resolution, "resolution");
        }

        /**
         * 返回一个已经取消的异步结果。
         */
        private static AsyncResolutionResult cancelledResult() {
            return new AsyncResolutionResult(Resolution.notApplicable(), null, null, true);
        }

        /**
         * 返回一个正常完成的异步结果。
         */
        private static AsyncResolutionResult completed(
            Resolution resolution,
            RuntimeException bindFailure,
            PersistedSessionRoute persistedRoute
        ) {
            return new AsyncResolutionResult(resolution, bindFailure, persistedRoute, false);
        }
    }

    /**
     * 会话解析阶段整理后的统一结果。
     *
     * @param binding 已解析出的绑定信息
     * @param invalidSession 会话是否明确无效
     * @param internalError 是否是上游不可用等内部错误
     */
    private record Resolution(Optional<ResolvedSession> binding, boolean invalidSession, boolean internalError) {
        /**
         * 确保绑定结果包装对象本身不为空。
         */
        private Resolution {
            Objects.requireNonNull(binding, "binding");
        }

        /**
         * 表示当前场景下没有可用解析结果，但也不算会话无效。
         */
        private static Resolution notApplicable() {
            return new Resolution(Optional.empty(), false, false);
        }

        /**
         * 表示会话已经明确无效。
         */
        private static Resolution invalid() {
            return new Resolution(Optional.empty(), true, false);
        }

        /**
         * 表示会话权威服务暂时不可用。
         */
        private static Resolution upstreamFailure() {
            return new Resolution(Optional.empty(), false, true);
        }

        /**
         * 表示成功解析出一个有效绑定。
         */
        private static Resolution binding(ResolvedSession binding) {
            return new Resolution(Optional.of(binding), false, false);
        }
    }

    /**
     * 心跳续租的三种结果：续租成功、发现自己已过期、或上游暂时不可用。
     */
    private enum HeartbeatRenewalResult {
        RENEWED,
        STALE,
        AUTHORITY_UNAVAILABLE
    }
}
