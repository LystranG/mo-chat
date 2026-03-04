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

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyChatServer(int port, EventBus eventBus, SslContext sslContext) {
        this(port, new ChatChannelInitializer(eventBus, sslContext));
    }

    public NettyChatServer(int port, ChatChannelInitializer channelInitializer) {
        this.port = port;
        this.channelInitializer = Objects.requireNonNull(channelInitializer, "channelInitializer");
    }

    public synchronized void start() throws InterruptedException {
        if (serverChannel != null) {
            return;
        }

        var transport = selectTransport();
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();

        try {
            serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(transport.serverChannelClass())
                .childHandler(channelInitializer)
                .bind(port)
                .sync()
                .channel();
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

    private static TransportSelection selectTransport() {
        var ioUringSelection = tryIoUringSelection();
        if (ioUringSelection != null) {
            return ioUringSelection;
        }

        if (Epoll.isAvailable()) {
            return new TransportSelection(
                new EpollEventLoopGroup(1),
                new EpollEventLoopGroup(),
                EpollServerSocketChannel.class
            );
        }

        return new TransportSelection(
            new NioEventLoopGroup(1),
            new NioEventLoopGroup(),
            NioServerSocketChannel.class
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

            EventLoopGroup boss = (EventLoopGroup) bossConstructor.newInstance(1);
            EventLoopGroup worker = (EventLoopGroup) workerConstructor.newInstance();
            @SuppressWarnings("unchecked")
            Class<? extends ServerSocketChannel> channelClass =
                (Class<? extends ServerSocketChannel>) serverChannelClass;
            return new TransportSelection(boss, worker, channelClass);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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

    private record TransportSelection(
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerSocketChannel> serverChannelClass
    ) {
    }
}
