package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Base64;
import java.util.Objects;

/**
 * 把已经拆包完成的客户端消息转成事件总线消息，交给后面的业务链路继续处理。
 */
public final class InboundRouterHandler extends SimpleChannelInboundHandler<InboundRouterHandler.InboundMessage> {
    public static final String DEFAULT_INBOUND_TOPIC = "connection.inbound";

    private final EventBus eventBus;
    private final String topic;

    /**
     * 使用默认主题转发客户端发上来的消息。
     */
    public InboundRouterHandler(EventBus eventBus) {
        this(eventBus, DEFAULT_INBOUND_TOPIC);
    }

    /**
     * 指定主题，把客户端发上来的消息投到事件总线。
     */
    public InboundRouterHandler(EventBus eventBus, String topic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    @Override
    /**
     * 把当前连接上的用户信息和消息内容一起编码成字符串事件。
     */
    protected void channelRead0(ChannelHandlerContext ctx, InboundMessage msg) {
        Long routingUserId = ctx.channel().attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
        String encodedBody = Base64.getEncoder().encodeToString(msg.body());
        // 已绑定的连接会把用户 ID 带上，方便下游直接按在线用户路由。
        String payload = routingUserId == null
            ? msg.msgType().name() + "|" + msg.serializerType().name() + "|" + encodedBody
            : routingUserId + "|" + msg.msgType().name() + "|" + msg.serializerType().name() + "|" + encodedBody;
        eventBus.publish(topic, payload);
    }

    /**
     * 一条已经过协议解码的客户端消息。
     *
     * @param msgType 消息类型
     * @param serializerType 消息体序列化方式
     * @param body 消息内容
     */
    public record InboundMessage(MsgType msgType, SerializerType serializerType, byte[] body) {
        /**
         * 确保消息的基础字段完整，避免后面的处理器收到半残数据。
         */
        public InboundMessage {
            Objects.requireNonNull(msgType, "msgType");
            Objects.requireNonNull(serializerType, "serializerType");
            Objects.requireNonNull(body, "body");
        }
    }
}
