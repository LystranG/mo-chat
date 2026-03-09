package com.github.lystran.mochat.runtime;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MochatRuntimeFactoryTest {
    @Test
    void buildsSelfSignedTlsContextWhenProfileEnablesIt() throws Exception {
        SslContext sslContext = MochatRuntimeFactory.buildSslContext("", "", true);

        assertNotNull(sslContext);
    }

    @Test
    void rejectsBlankCertificatePathsWhenSelfSignedDisabled() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> MochatRuntimeFactory.buildSslContext("", "", false)
        );

        assertEquals(
            "TLS certificate-path and private-key-path are required when TLS is enabled and self-signed is disabled",
            exception.getMessage()
        );
    }

    @Test
    void rejectsCertificateWithoutPrivateKeyEvenWhenSelfSignedEnabled() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> MochatRuntimeFactory.buildSslContext("cert.pem", "", true)
        );

        assertEquals(
            "TLS certificate-path and private-key-path must both be configured together",
            exception.getMessage()
        );
    }

    @Test
    void rejectsPrivateKeyWithoutCertificateEvenWhenSelfSignedEnabled() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> MochatRuntimeFactory.buildSslContext("", "key.pem", true)
        );

        assertEquals(
            "TLS certificate-path and private-key-path must both be configured together",
            exception.getMessage()
        );
    }

    @Test
    void rejectsDisablingMandatoryTls() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> MochatRuntimeFactory.buildMandatorySslContext(false, "", "", true)
        );

        assertEquals(
            "TLS is mandatory for chat TCP connections; mochat.tls.enabled=false is not supported",
            exception.getMessage()
        );
    }

    @Test
    void prefersExplicitCertificateOverSelfSignedWhenBothAreAvailable() throws Exception {
        SelfSignedCertificate explicitCertificate = new SelfSignedCertificate("localhost");
        try {
            SslContext explicitServerContext = MochatRuntimeFactory.buildSslContext(
                explicitCertificate.certificate().getAbsolutePath(),
                explicitCertificate.privateKey().getAbsolutePath(),
                true
            );
            SslContext trustedClientContext = buildTrustingClientContext(explicitCertificate.certificate());

            try (TlsHandshakeSession session = performTlsHandshake(explicitServerContext, trustedClientContext)) {
                X509Certificate expectedCertificate = readCertificate(explicitCertificate.certificate());
                X509Certificate presentedCertificate = session.presentedServerCertificate();

                assertArrayEquals(expectedCertificate.getEncoded(), presentedCertificate.getEncoded());
            }

            assertThrows(
                SSLException.class,
                () -> {
                    SslContext fallbackServerContext = MochatRuntimeFactory.buildSslContext("", "", true);
                    try (TlsHandshakeSession ignored = performTlsHandshake(fallbackServerContext, trustedClientContext)) {
                        // Handshake must fail because the client only trusts the explicit certificate above.
                    }
                }
            );
        } finally {
            explicitCertificate.delete();
        }
    }

    private static SslContext buildTrustingClientContext(File trustedCertificate) throws SSLException {
        return SslContextBuilder.forClient()
            .trustManager(trustedCertificate)
            .protocols("TLSv1.3")
            .build();
    }

    private static TlsHandshakeSession performTlsHandshake(SslContext serverContext, SslContext clientContext) throws Exception {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup clientGroup = new NioEventLoopGroup(1);
        Channel serverChannel = null;
        Channel acceptedChannel = null;
        Channel clientChannel = null;
        try {
            CompletableFuture<Channel> serverHandshake = new CompletableFuture<>();
            CompletableFuture<Channel> clientHandshake = new CompletableFuture<>();

            serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        SslHandler sslHandler = serverContext.newHandler(channel.alloc());
                        sslHandler.handshakeFuture().addListener(future -> completeHandshake(serverHandshake, channel, future));
                        channel.pipeline().addLast("tls", sslHandler);
                    }
                })
                .bind(InetAddress.getLoopbackAddress(), 0)
                .sync()
                .channel();

            int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

            clientChannel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        SslHandler sslHandler = clientContext.newHandler(channel.alloc(), "localhost", port);
                        sslHandler.handshakeFuture().addListener(future -> completeHandshake(clientHandshake, channel, future));
                        channel.pipeline().addLast("tls", sslHandler);
                    }
                })
                .connect(InetAddress.getLoopbackAddress(), port)
                .sync()
                .channel();

            Channel completedClientChannel = clientHandshake.get(5, TimeUnit.SECONDS);
            acceptedChannel = serverHandshake.get(5, TimeUnit.SECONDS);
            return new TlsHandshakeSession(bossGroup, workerGroup, clientGroup, serverChannel, acceptedChannel, completedClientChannel);
        } catch (Exception exception) {
            closeChannel(clientChannel);
            closeChannel(acceptedChannel);
            closeChannel(serverChannel);
            shutdownGroup(clientGroup);
            shutdownGroup(workerGroup);
            shutdownGroup(bossGroup);
            throw unwrapAsyncFailure(exception);
        }
    }

    private static void completeHandshake(CompletableFuture<Channel> handshake, Channel channel, io.netty.util.concurrent.Future<? super Channel> future) {
        if (future.isSuccess()) {
            handshake.complete(channel);
        } else {
            handshake.completeExceptionally(future.cause());
        }
    }

    private static X509Certificate readCertificate(File certificateFile) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(certificateFile)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
        }
    }

    private static Exception unwrapAsyncFailure(Exception exception) throws Exception {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        Throwable current = exception;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof Exception currentException) {
            return currentException;
        }
        return exception;
    }

    private static void closeChannel(Channel channel) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    private static void shutdownGroup(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class TlsHandshakeSession implements AutoCloseable {
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final EventLoopGroup clientGroup;
        private final Channel serverChannel;
        private final Channel acceptedChannel;
        private final Channel clientChannel;

        private TlsHandshakeSession(
            EventLoopGroup bossGroup,
            EventLoopGroup workerGroup,
            EventLoopGroup clientGroup,
            Channel serverChannel,
            Channel acceptedChannel,
            Channel clientChannel
        ) {
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.clientGroup = clientGroup;
            this.serverChannel = serverChannel;
            this.acceptedChannel = acceptedChannel;
            this.clientChannel = clientChannel;
        }

        private X509Certificate presentedServerCertificate() throws Exception {
            SslHandler sslHandler = clientChannel.pipeline().get(SslHandler.class);
            return (X509Certificate) sslHandler.engine().getSession().getPeerCertificates()[0];
        }

        @Override
        public void close() {
            closeChannel(clientChannel);
            closeChannel(acceptedChannel);
            closeChannel(serverChannel);
            shutdownGroup(clientGroup);
            shutdownGroup(workerGroup);
            shutdownGroup(bossGroup);
        }
    }
}
