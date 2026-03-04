package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.ConversationStateService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerTest {
    @Test
    void returnsPrivatePeerLatestReceivedSeqForRequester() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        when(stateService.findPrivatePeerLatestReceivedSeq(900L, 11L)).thenReturn(Optional.of(37L));

        ConversationController controller = new ConversationController(stateService);
        var response = controller.privatePeerLatestReceivedSeq(900L, 11L);

        assertEquals(HttpStatus.OK, response.getStatus());
        ConversationController.PrivatePeerLatestReceivedSeqResponse body = response.body();
        assertNotNull(body);
        assertEquals(900L, body.conversationId());
        assertEquals(37L, body.latestReceivedSeq());
        verify(stateService).findPrivatePeerLatestReceivedSeq(900L, 11L);
    }

    @Test
    void returnsLatestPersistedConversationState() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        when(stateService.findConversationLatestState(910L)).thenReturn(
            Optional.of(new ConversationStateService.ConversationLatestState(910L, 123L, 456789L))
        );

        ConversationController controller = new ConversationController(stateService);
        var response = controller.latestState(910L);

        assertEquals(HttpStatus.OK, response.getStatus());
        ConversationController.ConversationLatestStateResponse body = response.body();
        assertNotNull(body);
        assertEquals(910L, body.conversationId());
        assertEquals(123L, body.latestSeq());
        assertEquals(456789L, body.latestMessageTime());
        verify(stateService).findConversationLatestState(910L);
    }
}
