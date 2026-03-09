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

@ChannelHandler.Sharable
public final class SessionBindingHandler extends ChannelInboundHandlerAdapter {
    public static final AttributeKey<String> SESSION_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.sessionId");
    public static final AttributeKey<Long> USER_ID_ATTRIBUTE = AttributeKey.valueOf("mochat.userId");
    private static final String SESSION_INVALID_MESSAGE = "session invalid";

    private final SessionResolver sessionResolver;
    private final ChannelSessionRegistry<Channel> channelSessionRegistry;

    public SessionBindingHandler(
        SessionResolver sessionResolver,
        ChannelSessionRegistry<Channel> channelSessionRegistry
    ) {
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.channelSessionRegistry = Objects.requireNonNull(channelSessionRegistry, "channelSessionRegistry");
    }

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

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clearBinding(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        clearBinding(ctx.channel());
    }

    private Resolution resolveBinding(InboundRouterHandler.InboundMessage inboundMessage) {
        try {
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
            return sessionResolver.resolveUserId(sessionId)
                .map(userId -> Resolution.binding(new Binding(sessionId, userId)))
                .orElseGet(Resolution::invalid);
        } catch (InvalidProtocolBufferException ignored) {
            return Resolution.notApplicable();
        }
    }

    private void emitSessionInvalid(ChannelHandlerContext ctx) {
        byte[] body = Mochat.ErrorResponse.newBuilder()
            .setErrorCode(ErrorCode.SESSION_INVALID.code())
            .setMessage(SESSION_INVALID_MESSAGE)
            .build()
            .toByteArray();
        ctx.writeAndFlush(ChatChannelInitializer.encodeFrame(ctx.channel(), MsgType.ERROR_RESPONSE, body));
    }

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

    private void clearBinding(Channel channel) {
        String sessionId = channel.attr(SESSION_ID_ATTRIBUTE).get();
        Long userId = channel.attr(USER_ID_ATTRIBUTE).get();
        if (sessionId != null && userId != null) {
            channelSessionRegistry.unbind(sessionId, userId, channel);
        }
        channel.attr(SESSION_ID_ATTRIBUTE).set(null);
        channel.attr(USER_ID_ATTRIBUTE).set(null);
    }

    private record Binding(String sessionId, long userId) {
    }

    private record Resolution(Optional<Binding> binding, boolean invalidSession) {
        private Resolution {
            Objects.requireNonNull(binding, "binding");
        }

        private static Resolution notApplicable() {
            return new Resolution(Optional.empty(), false);
        }

        private static Resolution invalid() {
            return new Resolution(Optional.empty(), true);
        }

        private static Resolution binding(Binding binding) {
            return new Resolution(Optional.of(binding), false);
        }
    }
}
