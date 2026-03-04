package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.protocol.MsgType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

public final class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private final long idleTimeoutMillis;
    private ScheduledFuture<?> idleTimeoutTask;

    public HeartbeatHandler() {
        this(60);
    }

    public HeartbeatHandler(int idleTimeoutSeconds) {
        if (idleTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must be positive");
        }
        this.idleTimeoutMillis = TimeUnit.SECONDS.toMillis(idleTimeoutSeconds);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleIdleTimeout(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        scheduleIdleTimeout(ctx);

        if (msg instanceof InboundRouterHandler.InboundMessage inbound
            && inbound.msgType() == MsgType.CLIENT_HEARTBEAT) {
            ctx.fireUserEventTriggered("heartbeat-received");
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
    }

    private void scheduleIdleTimeout(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
        idleTimeoutTask = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelIdleTimeout() {
        if (idleTimeoutTask != null) {
            idleTimeoutTask.cancel(false);
            idleTimeoutTask = null;
        }
    }
}
