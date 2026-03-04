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

public final class NettyChatServer {
    private final int port;
    private final ChatChannelInitializer channelInitializer;
    private final TransportSelector transportSelector;
    private final ServerBinder serverBinder;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyChatServer(int port, EventBus eventBus, SslContext sslContext) {
        this(
            port,
            new ChatChannelInitializer(
                eventBus,
                Objects.requireNonNull(sslContext, "sslContext is required in production path")
            )
        );
    }

    public NettyChatServer(int port, ChatChannelInitializer channelInitializer) {
        this(port, channelInitializer, NettyChatServer::selectTransport, NettyChatServer::bindServerChannel);
    }

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

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
    }

    public static SslContext buildTls13Context(File certificateChain, File privateKey) throws SSLException {
        var provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
        return SslContextBuilder
            .forServer(certificateChain, privateKey)
            .sslProvider(provider)
            .protocols("TLSv1.3")
            .build();
    }

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

    private void startWithTransport(TransportSelection transport) throws InterruptedException {
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.serverChannel = serverBinder.bind(port, channelInitializer, transport);
    }

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

    private static void shutdownQuietly(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    @FunctionalInterface
    interface TransportSelector {
        TransportSelection select(boolean allowIoUring);
    }

    @FunctionalInterface
    interface ServerBinder {
        Channel bind(int port, ChatChannelInitializer channelInitializer, TransportSelection transport)
            throws InterruptedException;
    }

    record TransportSelection(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerSocketChannel> serverChannelClass,
        boolean ioUringTransport
    ) {
    }
}
