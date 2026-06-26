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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 负责把一条新 TCP 连接装配成完整的聊天处理链。
 */
public final class ChatChannelInitializer extends ChannelInitializer<Channel> {
    private static final Logger log = LoggerFactory.getLogger(ChatChannelInitializer.class);
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
     * 使用默认帧长和心跳参数创建处理链。
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
     * 指定最大帧长，其他参数走默认值。
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
     * 指定最大帧长和心跳超时，适合测试或特殊环境。
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
     * 指定完整的基础传输参数，但不注入显式绑定处理器。
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
     * 根据会话解析器和连接目录，现场拼一个默认的绑定处理器。
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
            sessionResolver,
            userChannelDirectory,
            null,
            maxFrameLength,
            heartbeatIntervalSeconds,
            heartbeatIdleTimeoutSeconds
        );
    }

    /**
     * 根据给定依赖创建带异步会话解析能力的默认绑定处理器。
     */
    public ChatChannelInitializer(
        EventBus eventBus,
        SslContext sslContext,
        SessionResolver sessionResolver,
        UserChannelDirectory<Channel> userChannelDirectory,
        Executor resolutionExecutor,
        int maxFrameLength,
        int heartbeatIntervalSeconds,
        int heartbeatIdleTimeoutSeconds
    ) {
        this(
            eventBus,
            sslContext,
            sessionResolver == null || userChannelDirectory == null
                ? null
                : new SessionBindingHandler(
                    sessionResolver,
                    new InMemoryChannelSessionRegistry<>(userChannelDirectory),
                    resolutionExecutor
                ),
            maxFrameLength,
            heartbeatIntervalSeconds,
            heartbeatIdleTimeoutSeconds
        );
    }

    /**
     * 直接使用外部传入的绑定处理器，组装完整处理链。
     */
    public ChatChannelInitializer(
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

    @Override
    /**
     * 按固定顺序组装管线：先 TLS、再拆包、再基础保护，最后才进入绑定和业务路由。
     */
    protected void initChannel(Channel channel) {
        log.info("新TCP连接 [{}] 进入，初始化管线", channel.id().asShortText());
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
        pipeline.addLast("protocolCodec", new ProtocolMessageCodec());
        pipeline.addLast("rateLimit", new RateLimitHandler());
        pipeline.addLast("heartbeat", new HeartbeatHandler(heartbeatIntervalSeconds, heartbeatIdleTimeoutSeconds));
        if (sessionBindingHandler != null) {
            pipeline.addLast("sessionBinding", sessionBindingHandler);
        }
        pipeline.addLast("inboundRouter", new InboundRouterHandler(eventBus));
    }


    @ChannelHandler.Sharable
    /**
     * 把二进制协议帧解成统一的“客户端消息对象”。
     */
    private static final class ProtocolMessageCodec extends MessageToMessageDecoder<ByteBuf> {
        @Override
        /**
         * 校验固定头，再把消息类型、序列化方式和消息体拆出来。
         */
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
         * 按协议里的数字编码还原消息类型。
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
         * 按协议里的数字编码还原序列化方式。
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
     * 把消息类型和消息体重新编码成网关 TCP 协议帧。
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
     * 构造服务端心跳消息体，让客户端知道服务端仍然在线。
     */
    static byte[] serverHeartbeatBody(long serverTimeMs) {
        return Mochat.Heartbeat.newBuilder()
            .setServerTimeMs(serverTimeMs)
            .build()
            .toByteArray();
    }
}
