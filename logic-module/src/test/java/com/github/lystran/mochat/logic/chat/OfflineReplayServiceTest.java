package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfflineReplayServiceTest {
    @Test
    void loginReplayDrainsUpTo50AndReplaysToOutboundTopic() {
        OfflineQueue offlineQueue = mock(OfflineQueue.class);
        EventBus eventBus = mock(EventBus.class);
        when(offlineQueue.drain(42L, 50)).thenReturn(List.of(
            "PRIVATE_MESSAGE|PROTOBUF|payload-1",
            "PRIVATE_MESSAGE|PROTOBUF|payload-2"
        ));

        OfflineReplayService replayService = new OfflineReplayService(offlineQueue, eventBus);
        replayService.replayOnLogin(42L);

        verify(offlineQueue).drain(42L, 50);
        verify(eventBus).publish("connection.outbound", "42|PRIVATE_MESSAGE|PROTOBUF|payload-1");
        verify(eventBus).publish("connection.outbound", "42|PRIVATE_MESSAGE|PROTOBUF|payload-2");
    }
}
