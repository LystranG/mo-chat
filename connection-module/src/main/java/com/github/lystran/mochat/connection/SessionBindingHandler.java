package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.session.ChannelSessionRegistry;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.Objects;
import java.util.Optional;

/**
 * 从带 sessionId 的业务消息里认出是谁在发消息，并把这个连接和用户绑起来。
 */
@ChannelHandler.Sharable
public final class SessionBindingHandler extends ChannelInboundHandlerAdapter {
    public static final AttributeKey<String> SESSION_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionId");
    public static final AttributeKey<Long> USER_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.userId");
    private static final String SESSION_INVALID_MESSAGE = "session invalid";

    private final SessionResolver sessionResolver;
    private final ChannelSessionRegistry<Channel> channelSessionRegistry;

    /**
     * 创建会话绑定处理器。
     */
    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry
    ) {
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.channelSessionRegistry = Objects.requireNonNull(channelSessionRegistry, "channelSessionRegistry");
    }

    /**
     * 尝试从业务消息里读出 sessionId，并把连接绑到对应用户上。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof InboundRouterHandler.InboundMessage inboundMessage) {
            Resolution resolution = resolveBinding(inboundMessage);
            if (resolution.invalidSession()) {
                clearBinding(ctx.channel());
                emitSessionInvalid(ctx);
                return;
            }
            resolution.binding().ifPresent(binding -> bind(ctx.channel(), binding));
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * 连接断开时清掉绑定关系，免得用户目录里留下失效连接。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clearBinding(ctx.channel());
        ctx.fireChannelInactive();
    }

    /**
     * 处理器移除时，也顺手清掉当前连接的绑定状态。
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        clearBinding(ctx.channel());
    }

    /**
     * 从可能带 sessionId 的业务消息里，解析出这条连接该怎么绑定。
     */
    private Resolution resolveBinding(InboundRouterHandler.InboundMessage inboundMessage) {
        try {
            // 只有私聊、群聊、收到回执这几类消息会带 sessionId；连接刚建好不算已登录，心跳照常放行。
            String sessionId = switch (inboundMessage.msgType()) {
                case PRIVATE_MESSAGE -> Mochat.PrivateMessageReq.parseFrom(inboundMessage.body()).getSessionId();
                case GROUP_MESSAGE -> Mochat.GroupMessageReq.parseFrom(inboundMessage.body()).getSessionId();
                case CLIENT_RECEIVE_ACK -> Mochat.ClientReceiveAck.parseFrom(inboundMessage.body()).getSessionId();
                default -> null;
            };
            if (sessionId == null) {
                return Resolution.notApplicable();
            }
            if (sessionId.isBlank()) {
                return Resolution.invalid();
            }
            // 只有这个 sessionId 真能换到用户 id，才把这条连接算成已经认出人。
            return sessionResolver.resolveUserId(sessionId)
                .map(userId -> Resolution.binding(new Binding(sessionId, userId)))
                .orElseGet(Resolution::invalid);
        } catch (InvalidProtocolBufferException ignored) {
            // 这里只管“能不能从消息里读出 sessionId”，消息内容本身坏没坏交给后面的统一错误处理。
            return Resolution.notApplicable();
        }
    }

    /**
     * 主动回一条统一的“session 无效”错误给当前连接。
     */
    private void emitSessionInvalid(ChannelHandlerContext ctx) {
        byte[] body = Mochat.ErrorResponse.newBuilder()
            .setErrorCode(ErrorCode.SESSION_INVALID.code())
            .setMessage(SESSION_INVALID_MESSAGE)
            .build()
            .toByteArray();
        ctx.writeAndFlush(ChatChannelInitializer.encodeFrame(ctx.channel(), MsgType.ERROR_RESPONSE, body));
    }

    /**
     * 把这条连接改绑到新的 session 和用户上。
     */
    private void bind(Channel channel, Binding binding) {
        String currentSessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long currentUserId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (binding.sessionId().equals(currentSessionId) && binding.userId() == (currentUserId == null ? 0L : currentUserId)) {
            return;
        }

        if (currentSessionId != null && currentUserId != null) {
            channelSessionRegistry.unbind(currentSessionId, currentUserId, channel);
        }

        channelSessionRegistry.bind(binding.sessionId(), binding.userId(), channel);
        channel.attr(SESSION_ID_ATTRIBUTE).set(binding.sessionId());
        channel.attr(USER_ID_ATTRIBUTE).set(binding.userId());
    }

    /**
     * 清掉这条连接上已经记住的 session 和用户。
     */
    private void clearBinding(Channel channel) {
        String sessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long userId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (sessionId != null && userId != null) {
            channelSessionRegistry.unbind(sessionId, userId, channel);
        }
        channel.attr(SESSION_ID_ATTRIBUTE).set(null);
        channel.attr(USER_ID_ATTRIBUTE).set(null);
    }

    /**
     * 表示这条连接最后要绑到哪个 session 和用户上。
     */
    private record Binding(String sessionId, long userId) {
    }

    /**
     * 表示解析 session 后的三种结果：这条消息不参与绑定、session 无效、可以绑定。
     */
    private record Resolution(Optional<Binding> binding, boolean invalidSession) {
        /**
         * 确保解析结果内部字段是完整的。
         */
        private Resolution {
            Objects.requireNonNull(binding, "binding");
        }

        /**
         * 表示这条消息不拿来认人。
         */
        private static Resolution notApplicable() {
            return new Resolution(Optional.empty(), false);
        }

        /**
         * 表示消息里带了 session，但这个 session 为空或查不到人。
         */
        private static Resolution invalid() {
            return new Resolution(Optional.empty(), true);
        }

        /**
         * 表示已经认出这条连接对应的是谁。
         */
        private static Resolution binding(Binding binding) {
            return new Resolution(Optional.of(binding), false);
        }
    }
}
