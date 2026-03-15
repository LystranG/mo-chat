package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.session.InMemoryChannelSessionRegistry;
import com.github.lystran.mochat.common.session.SessionResolver;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
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

/**
 * 负责给每条聊天 TCP 连接按顺序装上 TLS、解包、限流、身份绑定、心跳和把消息交给逻辑层的处理器。
 */
public final class ChatChannelInitializer extends ChannelInitializer<Channel> {
    private static final int PROTOCOL_MAGIC = 0x4D4F4348;
    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS = 60;

    private final EventBus eventBus;
    private final SslContext sslContext;
    private final SessionBindingHandler sessionBindingHandler;
    private final int maxFrameLength;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatIdleTimeoutSeconds;

    /**
     * 用默认帧长和心跳参数创建处理器安装器。
     */
    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext) {
        this(
            eventBus,
            sslContext,
            null,
            FrameConstants.DEFAULT_MAX_FRAME_LENGTH,
            DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS
        );
    }

    /**
     * 用自定义帧长和默认心跳参数创建处理器安装器。
     */
    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext, int maxFrameLength) {
        this(
            eventBus,
            sslContext,
            null,
            maxFrameLength,
            DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            DEFAULT_HEARTBEAT_IDLE_TIMEOUT_SECONDS
        );
    }

    /**
     * 用自定义帧长和空闲超时创建处理器安装器。
     */
    public ChatChannelInitializer(EventBus eventBus, SslContext sslContext, int maxFrameLength, int heartbeatIdleTimeoutSeconds) {
        this(
            eventBus,
            sslContext,
            null,
            maxFrameLength,
            DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            heartbeatIdleTimeoutSeconds
        );
    }

    /**
     * 用明确给出的心跳参数创建处理器安装器。
     */
    public ChatChannelInitializer(
        EventBus eventBus,
        SslContext sslContext,
        int maxFrameLength,
        int heartbeatIntervalSeconds,
        int heartbeatIdleTimeoutSeconds
    ) {
        this(
            eventBus,
            sslContext,
            null,
            maxFrameLength,
            heartbeatIntervalSeconds,
            heartbeatIdleTimeoutSeconds
        );
    }

    /**
     * 在需要按 session 认出用户时，创建会自动绑定用户的处理器安装器。
     */
    public ChatChannelInitializer(
        EventBus eventBus,
        SslContext sslContext,
        SessionResolver sessionResolver,
        UserChannelDirectory<Channel> userChannelDirectory,
        int maxFrameLength,
        int heartbeatIntervalSeconds,
        int heartbeatIdleTimeoutSeconds
    ) {
        this(
            eventBus,
            sslContext,
            sessionResolver == null || userChannelDirectory == null
                ? null
                : new SessionBindingHandler(sessionResolver, new InMemoryChannelSessionRegistry<>(userChannelDirectory)),
            maxFrameLength,
            heartbeatIntervalSeconds,
            heartbeatIdleTimeoutSeconds
        );
    }

    /**
     * 汇总所有构造重载的真正初始化逻辑。
     */
    private ChatChannelInitializer(
        EventBus eventBus,
        SslContext sslContext,
        SessionBindingHandler sessionBindingHandler,
        int maxFrameLength,
        int heartbeatIntervalSeconds,
        int heartbeatIdleTimeoutSeconds
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sslContext = sslContext;
        this.sessionBindingHandler = sessionBindingHandler;
        this.maxFrameLength = maxFrameLength;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.heartbeatIdleTimeoutSeconds = heartbeatIdleTimeoutSeconds;
    }

    /**
     * 按固定顺序把各个处理器装到新连接上。
     */
    @Override
    protected void initChannel(Channel channel) {
        var pipeline = channel.pipeline();
        if (sslContext != null) {
            pipeline.addLast("tls", sslContext.newHandler(channel.alloc()));
        }

        // 这里的先后顺序不能乱：先解包并拦掉坏消息和超速消息，再做身份绑定，
        // 最后才交给心跳和业务处理，免得还没认出是谁就把消息送进逻辑层。
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
            maxFrameLength,
            FrameConstants.BODY_LENGTH_OFFSET,
            FrameConstants.BODY_LENGTH_BYTES,
            0,
            0
        ));
        pipeline.addLast("protocolCodec", new ProtocolMessageCodec());
        pipeline.addLast("rateLimit", new RateLimitHandler());
        if (sessionBindingHandler != null) {
            pipeline.addLast("sessionBinding", sessionBindingHandler);
        }
        pipeline.addLast("heartbeat", new HeartbeatHandler(heartbeatIntervalSeconds, heartbeatIdleTimeoutSeconds));
        pipeline.addLast("inboundRouter", new InboundRouterHandler(eventBus));
    }

    /**
     * 把收到的二进制消息拆成逻辑层能继续处理的对象。
     */
    @ChannelHandler.Sharable
    private static final class ProtocolMessageCodec extends MessageToMessageDecoder<ByteBuf> {
        /**
         * 先检查固定头是否正确，再把字节消息改成统一的“收到消息”对象。
         */
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

        /**
         * 把协议里的消息类型编号换成代码里的枚举值。
         */
        private static MsgType msgTypeFromCode(int code) {
            for (var value : MsgType.values()) {
                if (value.code() == code) {
                    return value;
                }
            }
            throw new DecoderException("unknown msgType code: " + code);
        }

        /**
         * 把协议里的序列化编号换成代码里的枚举值。
         */
        private static SerializerType serializerFromCode(int code) {
            for (var value : SerializerType.values()) {
                if (value.code() == code) {
                    return value;
                }
            }
            throw new DecoderException("unknown serializer code: " + code);
        }
    }

    /**
     * 按固定头协议把消息类型和 protobuf 内容重新拼成可写回连接的二进制消息。
     */
    static ByteBuf encodeFrame(Channel channel, MsgType msgType, byte[] body) {
        ByteBuf frame = channel.alloc().buffer(FrameConstants.HEADER_LENGTH + body.length);
        frame.writeInt(PROTOCOL_MAGIC);
        frame.writeByte(FrameConstants.PROTOCOL_VERSION);
        frame.writeByte(msgType.code());
        frame.writeByte(SerializerType.PROTOBUF.code());
        frame.writeInt(body.length);
        frame.writeBytes(body);
        return frame;
    }

    /**
     * 生成服务端主动发给客户端的心跳消息内容。
     */
    static byte[] serverHeartbeatBody(long serverTimeMs) {
        return Mochat.Heartbeat.newBuilder()
            .setServerTimeMs(serverTimeMs)
            .build()
            .toByteArray();
    }
}
