package com.github.lystran.mochat.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public final class RateLimitHandler extends ChannelInboundHandlerAdapter {
    private final int maxMessagesPerSecond;

    private long currentWindowMillis;
    private int messageCount;

    public RateLimitHandler() {
        this(1_000);
    }

    public RateLimitHandler(int maxMessagesPerSecond) {
        this.maxMessagesPerSecond = maxMessagesPerSecond;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        long nowWindow = System.currentTimeMillis() / 1_000;
        if (nowWindow != currentWindowMillis) {
            currentWindowMillis = nowWindow;
            messageCount = 0;
        }

        messageCount++;
        if (messageCount > maxMessagesPerSecond) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        ctx.fireChannelRead(msg);
    }
}
