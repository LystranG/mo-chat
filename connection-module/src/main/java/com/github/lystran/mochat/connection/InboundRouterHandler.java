package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Base64;
import java.util.Objects;

public final class InboundRouterHandler extends SimpleChannelInboundHandler<InboundRouterHandler.InboundMessage> {
    public static final String DEFAULT_INBOUND_TOPIC = "connection.inbound";

    private final EventBus eventBus;
    private final String topic;

    public InboundRouterHandler(EventBus eventBus) {
        this(eventBus, DEFAULT_INBOUND_TOPIC);
    }

    public InboundRouterHandler(EventBus eventBus, String topic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InboundMessage msg) {
        String payload = msg.msgType().name()
            + "|"
            + msg.serializerType().name()
            + "|"
            + Base64.getEncoder().encodeToString(msg.body());
        eventBus.publish(topic, payload);
    }

    public record InboundMessage(MsgType msgType, SerializerType serializerType, byte[] body) {
        public InboundMessage {
            Objects.requireNonNull(msgType, "msgType");
            Objects.requireNonNull(serializerType, "serializerType");
            Objects.requireNonNull(body, "body");
        }
    }
}
