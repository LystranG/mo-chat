package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Base64;
import java.util.Objects;

/**
 * 把已经解包的客户端消息改成事件总线里的字符串，再交给逻辑层处理。
 */
public final class InboundRouterHandler extends SimpleChannelInboundHandler<InboundRouterHandler.InboundMessage> {
    public static final String DEFAULT_INBOUND_TOPIC = "connection.inbound";

    private final EventBus eventBus;
    private final String topic;

    /**
     * 用默认的“连接层收到消息”事件名创建路由处理器。
     */
    public InboundRouterHandler(EventBus eventBus) {
        this(eventBus, DEFAULT_INBOUND_TOPIC);
    }

    /**
     * 用指定的“连接层收到消息”事件名创建路由处理器。
     */
    public InboundRouterHandler(EventBus eventBus, String topic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    /**
     * 把收到的消息改成字符串事件；如果前面已经认出用户，就顺手带上用户 id。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InboundMessage msg) {
        Long routingUserId = ctx.channel().attr(SessionBindingHandler.USER_ID_ATTRIBUTE).get();
        String encodedBody = Base64.getEncoder().encodeToString(msg.body());
        // 还没认出是谁也照样往后送，让逻辑层统一判断到底是没登录还是消息本身有问题。
        String payload = routingUserId == null
            ? msg.msgType().name() + "|" + msg.serializerType().name() + "|" + encodedBody
            : routingUserId + "|" + msg.msgType().name() + "|" + msg.serializerType().name() + "|" + encodedBody;
        eventBus.publish(topic, payload);
    }

    /**
     * 表示已经通过连接层固定头检查的一条收到消息。
     */
    public record InboundMessage(MsgType msgType, SerializerType serializerType, byte[] body) {
        /**
         * 确保这条收到消息的必要字段都不为空。
         */
        public InboundMessage {
            Objects.requireNonNull(msgType, "msgType");
            Objects.requireNonNull(serializerType, "serializerType");
            Objects.requireNonNull(body, "body");
        }
    }
}
