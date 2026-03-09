package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.protocol.MsgType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

public final class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private final long heartbeatIntervalMillis;
    private final long idleTimeoutMillis;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> idleTimeoutTask;

    public HeartbeatHandler() {
        this(10, 60);
    }

    public HeartbeatHandler(int idleTimeoutSeconds) {
        this(10, idleTimeoutSeconds);
    }

    public HeartbeatHandler(int heartbeatIntervalSeconds, int idleTimeoutSeconds) {
        if (heartbeatIntervalSeconds <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalSeconds must be positive");
        }
        if (idleTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must be positive");
        }
        this.heartbeatIntervalMillis = TimeUnit.SECONDS.toMillis(heartbeatIntervalSeconds);
        this.idleTimeoutMillis = TimeUnit.SECONDS.toMillis(idleTimeoutSeconds);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleHeartbeat(ctx);
        scheduleIdleTimeout(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof InboundRouterHandler.InboundMessage inbound
            && inbound.msgType() == MsgType.CLIENT_HEARTBEAT) {
            scheduleIdleTimeout(ctx);
            ctx.fireUserEventTriggered("heartbeat-received");
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
    }

    private void scheduleHeartbeat(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        heartbeatTask = ctx.executor().scheduleAtFixedRate(() -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            ctx.writeAndFlush(
                ChatChannelInitializer.encodeFrame(
                    ctx.channel(),
                    MsgType.SERVER_HEARTBEAT,
                    ChatChannelInitializer.serverHeartbeatBody(System.currentTimeMillis())
                )
            );
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleIdleTimeout(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
        idleTimeoutTask = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void cancelIdleTimeout() {
        if (idleTimeoutTask != null) {
            idleTimeoutTask.cancel(false);
            idleTimeoutTask = null;
        }
    }
}
