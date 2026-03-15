package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.protocol.MsgType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * 负责服务端心跳发送和空闲超时检测，避免失联连接一直占着在线状态。
 */
public final class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    static final HeartbeatReceivedEvent HEARTBEAT_RECEIVED_EVENT = HeartbeatReceivedEvent.INSTANCE;
    static final HeartbeatTimeoutEvent HEARTBEAT_TIMEOUT_EVENT = HeartbeatTimeoutEvent.INSTANCE;
    private final long heartbeatIntervalMillis;
    private final long idleTimeoutMillis;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> idleTimeoutTask;

    /**
     * 使用默认心跳间隔和超时时间。
     */
    public HeartbeatHandler() {
        this(10, 60);
    }

    /**
     * 使用默认心跳间隔，自定义空闲超时。
     */
    public HeartbeatHandler(int idleTimeoutSeconds) {
        this(10, idleTimeoutSeconds);
    }

    /**
     * 指定服务端心跳频率和客户端空闲超时。
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

    @Override
    /**
     * 处理器装入后立刻开始心跳和超时计时。
     */
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleHeartbeat(ctx);
        scheduleIdleTimeout(ctx);
    }

    @Override
    /**
     * 收到客户端心跳时重置超时计时，并把“收到心跳”事件交给绑定处理器续租路由。
     */
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof InboundRouterHandler.InboundMessage inbound
            && inbound.msgType() == MsgType.CLIENT_HEARTBEAT) {
            scheduleIdleTimeout(ctx);
            ctx.fireUserEventTriggered(HEARTBEAT_RECEIVED_EVENT);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    /**
     * 连接断开时停止全部定时任务。
     */
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
        ctx.fireChannelInactive();
    }

    @Override
    /**
     * 处理器移除时也要清理定时任务。
     */
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelHeartbeat();
        cancelIdleTimeout();
    }

    /**
     * 定时向客户端发服务端心跳，帮助双方维持活跃状态。
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
     * 只要在规定时间内没再收到客户端心跳，就判定连接已经失联。
     */
    private void scheduleIdleTimeout(ChannelHandlerContext ctx) {
        cancelIdleTimeout();
        idleTimeoutTask = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.fireUserEventTriggered(HEARTBEAT_TIMEOUT_EVENT);
                if (ctx.channel().isActive()) {
                    ctx.close();
                }
            }
        }, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消服务端心跳任务。
     */
    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * 取消空闲超时任务。
     */
    private void cancelIdleTimeout() {
        if (idleTimeoutTask != null) {
            idleTimeoutTask.cancel(false);
            idleTimeoutTask = null;
        }
    }

    /**
     * 标记客户端刚刚发来了一次心跳。
     */
    static final class HeartbeatReceivedEvent {
        private static final HeartbeatReceivedEvent INSTANCE = new HeartbeatReceivedEvent();

        /**
         * 单例事件，不允许外部创建。
         */
        private HeartbeatReceivedEvent() {
        }
    }

    /**
     * 标记客户端已经超过超时窗口仍未回心跳。
     */
    static final class HeartbeatTimeoutEvent {
        private static final HeartbeatTimeoutEvent INSTANCE = new HeartbeatTimeoutEvent();

        /**
         * 单例事件，不允许外部创建。
         */
        private HeartbeatTimeoutEvent() {
        }
    }
}
