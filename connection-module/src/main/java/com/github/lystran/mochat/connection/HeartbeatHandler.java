package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.protocol.MsgType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof InboundRouterHandler.InboundMessage inbound
            && inbound.msgType() == MsgType.CLIENT_HEARTBEAT) {
            ctx.fireUserEventTriggered("heartbeat-received");
        }

        ctx.fireChannelRead(msg);
    }
}
