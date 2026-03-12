package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.InProcessEventBus;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyChatServerTest {
    @Test
    void requiresTlsContextInProductionConstructor() {
        assertThrows(NullPointerException.class, () -> new NettyChatServer(8080, new InProcessEventBus(), null));
    }

    @Test
    void fallsBackToDefaultTransportWhenIoUringBindFails() throws Exception {
        var eventBus = new InProcessEventBus();
        var initializer = new ChatChannelInitializer(eventBus, null);
        var ioUringTransport = new NettyChatServer.TransportSelection(
            new DefaultEventLoopGroup(1),
            new DefaultEventLoopGroup(1),
            NioServerSocketChannel.class,
            true
        );
        var defaultTransport = new NettyChatServer.TransportSelection(
            new DefaultEventLoopGroup(1),
            new DefaultEventLoopGroup(1),
            NioServerSocketChannel.class,
            false
        );

        var bindAttempts = new AtomicInteger();
        var serverChannel = new EmbeddedChannel();
        var server = new NettyChatServer(
            8080,
            initializer,
            allowIoUring -> allowIoUring ? ioUringTransport : defaultTransport,
            (host, port, channelInitializer, transport) -> {
                bindAttempts.incrementAndGet();
                if (transport.ioUringTransport()) {
                    throw new IllegalStateException("io_uring bootstrap failed");
                }
                return serverChannel;
            }
        );

        server.start();

        assertEquals(2, bindAttempts.get());
        server.stop();
        assertFalse(serverChannel.isOpen());
    }

    @Test
    void bindsConfiguredHostAndPort() throws Exception {
        var eventBus = new InProcessEventBus();
        var initializer = new ChatChannelInitializer(eventBus, null);
        var transport = new NettyChatServer.TransportSelection(
            new DefaultEventLoopGroup(1),
            new DefaultEventLoopGroup(1),
            NioServerSocketChannel.class,
            false
        );
        var boundHost = new AtomicReference<String>();
        var boundPort = new AtomicInteger();
        var serverChannel = new EmbeddedChannel();
        var server = new NettyChatServer(
            "127.0.0.2",
            8081,
            initializer,
            allowIoUring -> transport,
            (host, port, channelInitializer, selectedTransport) -> {
                boundHost.set(host);
                boundPort.set(port);
                return serverChannel;
            }
        );

        server.start();

        assertEquals("127.0.0.2", boundHost.get());
        assertEquals(8081, boundPort.get());
        server.stop();
        assertFalse(serverChannel.isOpen());
    }
}
