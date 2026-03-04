package com.github.lystran.mochat.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.Timer;

import java.util.concurrent.TimeUnit;

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

    private int availableTokens;
    private int refillAccumulator;
    private Timeout refillTimeout;

    public RateLimitHandler() {
        this(1_000, DEFAULT_TIMER, 100);
    }

    public RateLimitHandler(int maxMessagesPerSecond) {
        this(maxMessagesPerSecond, DEFAULT_TIMER, 100);
    }

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
    public void handlerAdded(ChannelHandlerContext ctx) {
        scheduleRefill(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelRefill();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelRefill();
    }

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

    private synchronized void refillTokens() {
        refillAccumulator += maxMessagesPerSecond;
        int replenished = refillAccumulator / refillIntervalsPerSecond;
        if (replenished <= 0) {
            return;
        }

        refillAccumulator %= refillIntervalsPerSecond;
        availableTokens = Math.min(maxMessagesPerSecond, availableTokens + replenished);
    }

    private void scheduleRefill(ChannelHandlerContext ctx) {
        refillTimeout = refillTimer.newTimeout(timeout -> {
            if (timeout.isCancelled() || !ctx.channel().isActive()) {
                return;
            }

            refillTokens();
            scheduleRefill(ctx);
        }, refillIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelRefill() {
        if (refillTimeout != null) {
            refillTimeout.cancel();
            refillTimeout = null;
        }
    }
}
