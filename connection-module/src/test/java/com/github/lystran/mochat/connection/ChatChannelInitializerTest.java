package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.event.InProcessEventBus;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatChannelInitializerTest {
    private static final int MAGIC = 0x4D4F4348;

    @Test
    void oversizedFrameIsRejected() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 32));

        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, new byte[64])));
    }

    @Test
    void parsesMsgTypeAndPublishesInboundEvent() throws Exception {
        var eventBus = new InProcessEventBus();
        var events = new CopyOnWriteArrayList<String>();
        try (var ignored = eventBus.subscribe(InboundRouterHandler.DEFAULT_INBOUND_TOPIC, events::add)) {
            var channel = new EmbeddedChannel(new ChatChannelInitializer(eventBus, null, 256));

            channel.writeInbound(buildFrame(MsgType.PRIVATE_MESSAGE, new byte[]{1, 2, 3}));
            assertEquals(1, events.size());
            assertEquals("PRIVATE_MESSAGE|PROTOBUF|AQID", events.get(0));
        }
    }

    @Test
    void rejectsInvalidProtocolMagic() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256));

        assertThrows(DecoderException.class, () -> channel.writeInbound(buildFrame(0x01020304, MsgType.PRIVATE_MESSAGE, new byte[]{1})));
    }

    @Test
    void heartbeatFramesAreNotPublishedAsInboundEvents() throws Exception {
        var eventBus = new InProcessEventBus();
        var events = new CopyOnWriteArrayList<String>();
        try (var ignored = eventBus.subscribe(InboundRouterHandler.DEFAULT_INBOUND_TOPIC, events::add)) {
            var channel = new EmbeddedChannel(new ChatChannelInitializer(eventBus, null, 256, 1));

            channel.writeInbound(buildFrame(MsgType.CLIENT_HEARTBEAT, new byte[0]));

            assertEquals(0, events.size());
        }
    }

    @Test
    void closesIdleConnectionWhenHeartbeatsStop() {
        var channel = new EmbeddedChannel(new ChatChannelInitializer(new InProcessEventBus(), null, 256, 1));

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    private static ByteBuf buildFrame(MsgType msgType, byte[] body) {
        return buildFrame(MAGIC, msgType, body);
    }

    private static ByteBuf buildFrame(int magic, MsgType msgType, byte[] body) {
        return Unpooled.buffer(FrameConstants.HEADER_LENGTH + body.length)
            .writeInt(magic)
            .writeByte(FrameConstants.PROTOCOL_VERSION)
            .writeByte(msgType.code())
            .writeByte(SerializerType.PROTOBUF.code())
            .writeInt(body.length)
            .writeBytes(body);
    }
}
