package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.protocol.MsgType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * 负责定时给客户端发心跳，并在太久没收到客户端心跳时关掉连接。
 */
public final class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private final long heartbeatIntervalMillis;
    private final long idleTimeoutMillis;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> idleTimeoutTask;

    /**
     * 用默认心跳频率和超时时间创建处理器。
     */
    public HeartbeatHandler() {
        this(10, 60);
    }

    /**
     * 用默认心跳频率和自定义超时时间创建处理器。
     */
    public HeartbeatHandler(int idleTimeoutSeconds) {
        this(10, idleTimeoutSeconds);
    }

    /**
     * 用明确给出的心跳频率和超时时间创建处理器。
     */
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

    /**
     * 处理器装到连接上后，启动发心跳和超时检查。
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleHeartbeat(ctx);
        scheduleIdleTimeout(ctx);
    }

    /**
     * 收到客户端心跳时重置超时计时；其他消息继续往后传。
     */
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

    /**
     * 连接断开时停掉所有定时任务，免得回调继续碰已经失效的连接。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
        ctx.fireChannelInactive();
    }

    /**
     * 处理器被移除时也顺手清掉内部定时任务。
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
    }

    /**
     * 周期性给客户端发心跳，用来确认连接还活着。
     */
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

    /**
     * 重新安排“多久没收到客户端心跳就关连接”的任务。
     */
    private void scheduleIdleTimeout(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
        idleTimeoutTask = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 停掉发心跳的定时任务。
     */
    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * 停掉超时关闭连接的定时任务。
     */
    private void cancelIdleTimeout() {
        if (idleTimeoutTask != null) {
            idleTimeoutTask.cancel(false);
            idleTimeoutTask = null;
        }
    }
}
