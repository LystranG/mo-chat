package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @Test
    void publishFailureReEnqueuesCurrentAndRemainingMessages() {
        OfflineQueue offlineQueue = mock(OfflineQueue.class);
        EventBus eventBus = mock(EventBus.class);
        when(offlineQueue.drain(42L, 50)).thenReturn(List.of(
            "PRIVATE_MESSAGE|PROTOBUF|payload-1",
            "PRIVATE_MESSAGE|PROTOBUF|payload-2",
            "PRIVATE_MESSAGE|PROTOBUF|payload-3"
        ));
        doThrow(new RuntimeException("publish failed"))
            .when(eventBus)
            .publish("connection.outbound", "42|PRIVATE_MESSAGE|PROTOBUF|payload-2");

        OfflineReplayService replayService = new OfflineReplayService(offlineQueue, eventBus);
        replayService.replayOnLogin(42L);

        InOrder inOrder = inOrder(eventBus, offlineQueue);
        inOrder.verify(eventBus).publish("connection.outbound", "42|PRIVATE_MESSAGE|PROTOBUF|payload-1");
        inOrder.verify(eventBus).publish("connection.outbound", "42|PRIVATE_MESSAGE|PROTOBUF|payload-2");
        inOrder.verify(offlineQueue).enqueue(42L, "PRIVATE_MESSAGE|PROTOBUF|payload-2", 50);
        inOrder.verify(offlineQueue).enqueue(42L, "PRIVATE_MESSAGE|PROTOBUF|payload-3", 50);
        verifyNoMoreInteractions(eventBus);
    }
}
