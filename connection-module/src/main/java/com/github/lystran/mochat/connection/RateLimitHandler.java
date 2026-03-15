package com.github.lystran.mochat.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 简单的每连接令牌桶限流器，防止单条连接在短时间内打爆网关。
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

    /**
     * 当前还能放行多少条消息。
     */
    private int availableTokens;
    /**
     * 把每个小时间片补进来的额度先累起来，凑够整数后再补回令牌桶。
     */
    private int refillAccumulator;
    private Timeout refillTimeout;

    /**
     * 使用默认每秒上限和补充节奏。
     */
    public RateLimitHandler() {
        this(1_000, DEFAULT_TIMER, 100);
    }

    /**
     * 指定每秒上限，其他参数走默认值。
     */
    public RateLimitHandler(int maxMessagesPerSecond) {
        this(maxMessagesPerSecond, DEFAULT_TIMER, 100);
    }

    /**
     * 构造一个可测试的限流器实现。
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

    @Override
    /**
     * 处理器装入管线后开始定时补充令牌。
     */
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleRefill(ctx);
    }

    @Override
    /**
     * 连接断开时停止补充任务。
     */
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelRefill();
        ctx.fireChannelInactive();
    }

    @Override
    /**
     * 处理器被移除时也要取消补充任务，避免泄漏。
     */
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelRefill();
    }

    @Override
    /**
     * 没有令牌时直接关闭连接，防止异常洪峰继续灌进后续处理器。
     */
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
     * 按固定时间片把额度补回令牌桶。
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
     * 安排下一次令牌补充。
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
     * 取消当前连接的令牌补充任务。
     */
    private void cancelRefill() {
        if (refillTimeout != null) {
            refillTimeout.cancel();
            refillTimeout = null;
        }
    }
}
