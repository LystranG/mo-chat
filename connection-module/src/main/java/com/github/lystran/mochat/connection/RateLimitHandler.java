package com.github.lystran.mochat.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 大致限制单个连接每秒能发多少条消息，超了就直接断开。
 */
public final class RateLimitHandler extends ChannelInboundHandlerAdapter {
    private static final Timer DEFAULT_TIMER = new HashedWheelTimer(
        r -> {
            var thread = new Thread(r, "connection-rate-limit-refill");
            thread.setDaemon(true);
            return thread;
        },
        100,
        TimeUnit.MILLISECONDS
    );

    private final int maxMessagesPerSecond;
    private final Timer refillTimer;
    private final long refillIntervalMillis;
    private final int refillIntervalsPerSecond;

    // 当前这个连接还允许立刻发进来的消息数。
    private int availableTokens;
    // 把多次小步补充累计起来，凑成真正能加回去的整条消息额度。
    private int refillAccumulator;
    private Timeout refillTimeout;

    /**
     * 用默认的每秒上限创建限流处理器。
     */
    public RateLimitHandler() {
        this(1_000, DEFAULT_TIMER, 100);
    }

    /**
     * 用自定义的每秒上限创建限流处理器。
     */
    public RateLimitHandler(int maxMessagesPerSecond) {
        this(maxMessagesPerSecond, DEFAULT_TIMER, 100);
    }

    /**
     * 用明确给出的定时器和补充间隔创建限流处理器，主要给测试或调参用。
     */
    RateLimitHandler(int maxMessagesPerSecond, Timer refillTimer, long refillIntervalMillis) {
        if (maxMessagesPerSecond <= 0) {
            throw new IllegalArgumentException("maxMessagesPerSecond must be positive");
        }
        if (refillIntervalMillis <= 0 || 1_000 % refillIntervalMillis != 0) {
            throw new IllegalArgumentException("refillIntervalMillis must be a positive divisor of 1000");
        }

        this.maxMessagesPerSecond = maxMessagesPerSecond;
        this.refillTimer = refillTimer;
        this.refillIntervalMillis = refillIntervalMillis;
        this.refillIntervalsPerSecond = (int) (1_000 / refillIntervalMillis);
        this.availableTokens = maxMessagesPerSecond;
    }

    /**
     * 处理器装到连接上后，开始定时补回可发送条数。
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleRefill(ctx);
    }

    /**
     * 连接断开时停掉补充任务，并继续把断开事件往后传。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelRefill();
        ctx.fireChannelInactive();
    }

    /**
     * 处理器移除时取消还没结束的补充任务。
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelRefill();
    }

    /**
     * 先扣掉一条可发送额度；如果额度用完了，就丢掉这条消息并关连接。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean allowed;
        synchronized (this) {
            allowed = availableTokens > 0;
            if (allowed) {
                availableTokens--;
            }
        }

        if (!allowed) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        ctx.fireChannelRead(msg);
    }

    /**
     * 按当前补充节奏，把累计下来的额度换算回真正可用的条数。
     */
    private synchronized void refillTokens() {
        refillAccumulator += maxMessagesPerSecond;
        int replenished = refillAccumulator / refillIntervalsPerSecond;
        if (replenished <= 0) {
            return;
        }

        refillAccumulator %= refillIntervalsPerSecond;
        availableTokens = Math.min(maxMessagesPerSecond, availableTokens + replenished);
    }

    /**
     * 安排下一次补回可发送条数的任务。
     */
    private void scheduleRefill(ChannelHandlerContext ctx) {
        refillTimeout = refillTimer.newTimeout(timeout -> {
            if (timeout.isCancelled() || !ctx.channel().isActive()) {
                return;
            }

            refillTokens();
            scheduleRefill(ctx);
        }, refillIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消当前挂着的补充任务。
     */
    private void cancelRefill() {
        if (refillTimeout != null) {
            refillTimeout.cancel();
            refillTimeout = null;
        }
    }
}
