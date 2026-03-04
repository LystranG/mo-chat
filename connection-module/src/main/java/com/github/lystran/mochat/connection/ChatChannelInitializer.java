package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.ssl.SslContext;

import java.util.List;
import java.util.Objects;

public final class ChatChannelInitializer extends ChannelInitializer<Channel> {
    private static final int PROTOCOL_MAGIC = 0x4D4F4348;
    private static final int DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS = 60;

    private final EventBus eventBus;
    private final SslContext sslContext;
    private final int maxFrameLength;
    private final int heartbeatIdleTimeoutSeconds;

    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext) {
        this(eventBus, sslContext, FrameConstants.DEFAULT_MAX_FRAME_LENGTH, DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS);
    }

    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext, int maxFrameLength) {
        this(eventBus, sslContext, maxFrameLength, DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS);
    }

    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext, int maxFrameLength, int heartbeatIdleTimeoutSeconds) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sslContext = sslContext;
        this.maxFrameLength = maxFrameLength;
        this.heartbeatIdleTimeoutSeconds = heartbeatIdleTimeoutSeconds;
    }

    @Override
    protected void initChannel(Channel channel) {
        var pipeline = channel.pipeline();
        if (sslContext != null) {
            pipeline.addLast("tls", sslContext.newHandler(channel.alloc()));
        }

        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
            maxFrameLength,
            FrameConstants.BODY_LENGTH_OFFSET,
            FrameConstants.BODY_LENGTH_BYTES,
            0,
            0
        ));
        pipeline.addLast("protobufDecodePlaceholder", new ProtobufDecodePlaceholderHandler());
        pipeline.addLast("rateLimit", new RateLimitHandler());
        pipeline.addLast("heartbeat", new HeartbeatHandler(heartbeatIdleTimeoutSeconds));
        pipeline.addLast("inboundRouter", new InboundRouterHandler(eventBus));
    }

    @ChannelHandler.Sharable
    private static final class ProtobufDecodePlaceholderHandler extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            if (msg.readableBytes() < FrameConstants.HEADER_LENGTH) {
                throw new DecoderException("frame shorter than protocol header");
            }

            int magic = msg.getInt(FrameConstants.MAGIC_OFFSET);
            if (magic != PROTOCOL_MAGIC) {
                throw new DecoderException("invalid protocol magic: 0x" + Integer.toHexString(magic));
            }

            int protocolVersion = msg.getUnsignedByte(FrameConstants.VERSION_OFFSET);
            if (protocolVersion != FrameConstants.PROTOCOL_VERSION) {
                throw new DecoderException("unsupported protocol version: " + protocolVersion);
            }

            int msgTypeCode = msg.getUnsignedByte(FrameConstants.MSG_TYPE_OFFSET);
            int serializerCode = msg.getUnsignedByte(FrameConstants.SERIALIZER_OFFSET);
            int bodyLength = msg.getInt(FrameConstants.BODY_LENGTH_OFFSET);

            if (bodyLength < 0) {
                throw new DecoderException("negative body length: " + bodyLength);
            }

            var body = new byte[bodyLength];
            msg.getBytes(FrameConstants.HEADER_LENGTH, body);

            out.add(new InboundRouterHandler.InboundMessage(
                msgTypeFromCode(msgTypeCode),
                serializerFromCode(serializerCode),
                body
            ));
        }

        private static MsgType msgTypeFromCode(int code) {
            for (var value : MsgType.values()) {
                if (value.code() == code) {
                    return value;
                }
            }
            throw new DecoderException("unknown msgType code: " + code);
        }

        private static SerializerType serializerFromCode(int code) {
            for (var value : SerializerType.values()) {
                if (value.code() == code) {
                    return value;
                }
            }
            throw new DecoderException("unknown serializer code: " + code);
        }
    }
}
