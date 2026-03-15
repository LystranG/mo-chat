package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.EventBus;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.SSLException;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 封装聊天 TCP 服务的启动、传输选择和关闭逻辑。
 */
public final class NettyChatServer {
    private static final String DEFAULT_HOST = "0.0.0.0";

    private final String host;
    private final int port;
    private final ChatChannelInitializer channelInitializer;
    private final TransportSelector transportSelector;
    private final ServerBinder serverBinder;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * 用默认主机地址创建 Netty 服务，并要求外部已经准备好 TLS 上下文。
     */
    public NettyChatServer(int port, EventBus eventBus, SslContext sslContext) {
        this(
            DEFAULT_HOST,
            port,
            new ChatChannelInitializer(
                eventBus,
                Objects.requireNonNull(sslContext, "sslContext is required in production path")
            )
        );
    }

    /**
     * 用默认主机地址和现成的处理链创建服务。
     */
    public NettyChatServer(int port, ChatChannelInitializer channelInitializer) {
        this(DEFAULT_HOST, port, channelInitializer);
    }

    /**
     * 指定监听地址、端口和处理链。
     */
    public NettyChatServer(String host, int port, ChatChannelInitializer channelInitializer) {
        this(host, port, channelInitializer, NettyChatServer::selectTransport, NettyChatServer::bindServerChannel);
    }

    /**
     * 供测试注入自定义传输选择器和绑定器。
     */
    NettyChatServer(
        int port,
        ChatChannelInitializer channelInitializer,
        TransportSelector transportSelector,
        ServerBinder serverBinder
    ) {
        this(DEFAULT_HOST, port, channelInitializer, transportSelector, serverBinder);
    }

    /**
     * 供测试或特殊装配使用的完整构造器。
     */
    NettyChatServer(
        String host,
        int port,
        ChatChannelInitializer channelInitializer,
        TransportSelector transportSelector,
        ServerBinder serverBinder
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.channelInitializer = Objects.requireNonNull(channelInitializer, "channelInitializer");
        this.transportSelector = Objects.requireNonNull(transportSelector, "transportSelector");
        this.serverBinder = Objects.requireNonNull(serverBinder, "serverBinder");
    }

    /**
     * 启动聊天 TCP 服务，优先尝试 io_uring，失败后再回退到其他传输。
     */
    public synchronized void start() throws InterruptedException {
        if (serverChannel != null) {
            return;
        }

        var preferredTransport = transportSelector.select(true);
        try {
            startWithTransport(preferredTransport);
            return;
        } catch (InterruptedException interruptedException) {
            shutdownGroups();
            throw interruptedException;
        } catch (RuntimeException runtimeException) {
            if (!preferredTransport.ioUringTransport()) {
                shutdownGroups();
                throw runtimeException;
            }

            shutdownGroups();
        }

        var fallbackTransport = transportSelector.select(false);
        try {
            startWithTransport(fallbackTransport);
        } catch (InterruptedException interruptedException) {
            shutdownGroups();
            throw interruptedException;
        } catch (RuntimeException runtimeException) {
            shutdownGroups();
            throw runtimeException;
        }
    }

    /**
     * 停止监听并关闭 boss/worker 线程组。
     */
    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
    }

    /**
     * 构造只允许 TLS 1.3 的服务端 TLS 上下文。
     */
    public static SslContext buildTls13Context(File certificateChain, File privateKey) throws SSLException {
        var provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
        return SslContextBuilder
            .forServer(certificateChain, privateKey)
            .sslProvider(provider)
            .protocols("TLSv1.3")
            .build();
    }

    /**
     * 根据当前环境选择最合适的 Netty 传输实现。
     */
    private static TransportSelection selectTransport(boolean allowIoUring) {
        if (allowIoUring) {
            var ioUringSelection = tryIoUringSelection();
            if (ioUringSelection != null) {
                return ioUringSelection;
            }
        }

        if (Epoll.isAvailable()) {
            return new TransportSelection(
                new EpollEventLoopGroup(1),
                new EpollEventLoopGroup(),
                EpollServerSocketChannel.class,
                false
            );
        }

        return new TransportSelection(
            new NioEventLoopGroup(1),
            new NioEventLoopGroup(),
            NioServerSocketChannel.class,
            false
        );
    }

    /**
     * 通过反射探测 io_uring 是否真的可用，避免把可选依赖写死成启动前提。
     */
    private static TransportSelection tryIoUringSelection() {
        try {
            Class<?> ioUringClass = Class.forName("io.netty.incubator.channel.uring.IOUring");
            Method isAvailableMethod = ioUringClass.getMethod("isAvailable");
            boolean available = (boolean) isAvailableMethod.invoke(null);
            if (!available) {
                return null;
            }

            Class<?> eventLoopGroupClass = Class.forName("io.netty.incubator.channel.uring.IOUringEventLoopGroup");
            Constructor<?> bossConstructor = eventLoopGroupClass.getConstructor(int.class);
            Constructor<?> workerConstructor = eventLoopGroupClass.getConstructor();

            Class<?> serverChannelClass = Class.forName("io.netty.incubator.channel.uring.IOUringServerSocketChannel");
            if (!ServerSocketChannel.class.isAssignableFrom(serverChannelClass)) {
                return null;
            }

            EventLoopGroup boss = null;
            EventLoopGroup worker = null;
            try {
                boss = (EventLoopGroup) bossConstructor.newInstance(1);
                worker = (EventLoopGroup) workerConstructor.newInstance();
            } catch (ReflectiveOperationException | RuntimeException innerException) {
                shutdownQuietly(worker);
                shutdownQuietly(boss);
                return null;
            }

            @SuppressWarnings("unchecked")
            Class<? extends ServerSocketChannel> channelClass =
                (Class<? extends ServerSocketChannel>) serverChannelClass;
            return new TransportSelection(boss, worker, channelClass, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * 用选中的传输实现真正启动底层监听。
     */
    private void startWithTransport(TransportSelection transport) throws InterruptedException {
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.serverChannel = serverBinder.bind(host, port, channelInitializer, transport);
    }

    /**
     * 把监听地址、线程组和处理链交给 Netty 进行绑定。
     */
    private static Channel bindServerChannel(
        String host,
        int port,
        ChatChannelInitializer channelInitializer,
        TransportSelection transport
    ) throws InterruptedException {
        return new ServerBootstrap()
            .group(transport.bossGroup(), transport.workerGroup())
            .channel(transport.serverChannelClass())
            .childHandler(channelInitializer)
            .bind(host, port)
            .sync()
            .channel();
    }

    /**
     * 关闭 boss 和 worker 线程组。
     */
    private void shutdownGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
    }

    /**
     * 在半初始化状态下静默关闭线程组，避免回退流程留下资源。
     */
    private static void shutdownQuietly(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    @FunctionalInterface
    /**
     * 选择底层传输实现的策略接口。
     */
    interface TransportSelector {
        /**
         * 返回本次启动要使用的传输实现。
         */
        TransportSelection select(boolean allowIoUring);
    }

    @FunctionalInterface
    /**
     * 抽象出真正的 bind 动作，方便测试替换。
     */
    interface ServerBinder {
        /**
         * 按指定传输实现完成监听绑定。
         */
        Channel bind(String host, int port, ChatChannelInitializer channelInitializer, TransportSelection transport)
            throws InterruptedException;
    }

    /**
     * 一次启动尝试最终选中的底层传输信息。
     *
     * @param bossGroup 负责接收新连接的线程组
     * @param workerGroup 负责连接 IO 的线程组
     * @param serverChannelClass 对应的 ServerSocketChannel 实现
     * @param ioUringTransport 这次是否真的用了 io_uring
     */
    record TransportSelection(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerSocketChannel> serverChannelClass,
        boolean ioUringTransport
    ) {
    }
}
