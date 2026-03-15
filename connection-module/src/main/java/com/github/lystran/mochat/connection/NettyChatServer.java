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
 * 负责挑一个当前机器能用的底层网络实现，并启动或关闭聊天 TCP 服务。
 */
public final class NettyChatServer {
    private final int port;
    private final ChatChannelInitializer channelInitializer;
    private final TransportSelector transportSelector;
    private final ServerBinder serverBinder;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * 用默认的底层网络选择规则，以及带 TLS 的连接处理顺序创建聊天服务。
     */
    public NettyChatServer(int port, EventBus eventBus, SslContext sslContext) {
        this(
            port,
            new ChatChannelInitializer(
                eventBus,
                Objects.requireNonNull(sslContext, "sslContext is required in production path")
            )
        );
    }

    /**
     * 用默认的底层网络选择规则创建聊天服务。
     */
    public NettyChatServer(int port, ChatChannelInitializer channelInitializer) {
        this(port, channelInitializer, NettyChatServer::selectTransport, NettyChatServer::bindServerChannel);
    }

    /**
     * 把“选哪种底层网络实现”和“怎么绑定端口”抽出来，方便测试替换。
     */
    NettyChatServer(
        int port,
        ChatChannelInitializer channelInitializer,
        TransportSelector transportSelector,
        ServerBinder serverBinder
    ) {
        this.port = port;
        this.channelInitializer = Objects.requireNonNull(channelInitializer, "channelInitializer");
        this.transportSelector = Objects.requireNonNull(transportSelector, "transportSelector");
        this.serverBinder = Objects.requireNonNull(serverBinder, "serverBinder");
    }

    /**
     * 启动聊天 TCP 服务；如果最先尝试的 io_uring 起不来，就改用 epoll 或 NIO。
     */
    public synchronized void start() throws InterruptedException {
        if (serverChannel != null) {
            return;
        }

        // 固定做法是先试 io_uring，起不来就改用 epoll/NIO；换哪种底层实现，不会改变上层收发协议。
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
     * 关闭监听端口和底层线程组。
     */
    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
    }

    /**
     * 构造仅允许 TLS 1.3 的服务端 SSL 上下文。
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
     * 选出当前环境里能用的底层网络实现。
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
     * 用反射试探并初始化 io_uring 这一套底层网络实现。
     */
    private static TransportSelection tryIoUringSelection() {
        try {
            // 这里不用写死依赖，而是运行时试一下：机器不支持 io_uring 也还能正常启动。
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
                // 只要任意一组线程没建好，就把已经建出来的先收掉，再改用普通实现。
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
     * 用选中的底层网络实现创建线程组并绑定监听端口。
     */
    private void startWithTransport(TransportSelection transport) throws InterruptedException {
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.serverChannel = serverBinder.bind(port, channelInitializer, transport);
    }

    /**
     * 按给定的底层网络实现配置并绑定 Netty 服务端连接。
     */
    private static Channel bindServerChannel(
        int port,
        ChatChannelInitializer channelInitializer,
        TransportSelection transport
    ) throws InterruptedException {
        return new ServerBootstrap()
            .group(transport.bossGroup(), transport.workerGroup())
            .channel(transport.serverChannelClass())
            .childHandler(channelInitializer)
            .bind(port)
            .sync()
            .channel();
    }

    /**
     * 关闭当前持有的 boss/worker 事件循环组。
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
     * 在试探或改用别的实现时，静默收掉临时创建的线程组。
     */
    private static void shutdownQuietly(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    /**
     * 把“挑底层网络实现”这件事抽出来，测试时好替换。
     */
    @FunctionalInterface
    interface TransportSelector {
        TransportSelection select(boolean allowIoUring);
    }

    /**
     * 把“真正去绑定端口”这件事抽出来，测试时好替换。
     */
    @FunctionalInterface
    interface ServerBinder {
        Channel bind(int port, ChatChannelInitializer channelInitializer, TransportSelection transport)
            throws InterruptedException;
    }

    /**
     * 描述一次底层网络选择的结果，以及跟它一起创建出来的资源。
     */
    record TransportSelection(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerSocketChannel> serverChannelClass,
        boolean ioUringTransport
    ) {
    }
}
