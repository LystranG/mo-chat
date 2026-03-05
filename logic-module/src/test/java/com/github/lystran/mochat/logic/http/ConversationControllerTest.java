package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerTest {
    @Test
    void returnsPrivatePeerLatestReceivedSeqForRequester() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(stateService.findPrivatePeerLatestReceivedSeq(900L, 11L)).thenReturn(Optional.of(37L));

        ConversationController controller = new ConversationController(stateService, sessionService);
        var response = controller.privatePeerLatestReceivedSeq(900L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        ConversationController.PrivatePeerLatestReceivedSeqResponse body =
            (ConversationController.PrivatePeerLatestReceivedSeqResponse) response.body();
        assertNotNull(body);
        assertEquals(900L, body.conversationId());
        assertEquals(37L, body.latestReceivedSeq());
        verify(sessionService).resolveUserId("session-ok");
        verify(stateService).findPrivatePeerLatestReceivedSeq(900L, 11L);
    }

    @Test
    void returnsLatestPersistedConversationState() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(stateService.hasConversationAccess(910L, 11L)).thenReturn(true);
        when(stateService.findConversationLatestState(910L)).thenReturn(
            Optional.of(new ConversationStateService.ConversationLatestState(910L, 123L, 456789L))
        );

        ConversationController controller = new ConversationController(stateService, sessionService);
        var response = controller.latestState(910L, "session-ok");

        assertEquals(HttpStatus.OK, response.getStatus());
        ConversationController.ConversationLatestStateResponse body =
            (ConversationController.ConversationLatestStateResponse) response.body();
        assertNotNull(body);
        assertEquals(910L, body.conversationId());
        assertEquals(123L, body.latestSeq());
        assertEquals(456789L, body.latestMessageTime());
        verify(sessionService).resolveUserId("session-ok");
        verify(stateService).hasConversationAccess(910L, 11L);
        verify(stateService).findConversationLatestState(910L);
    }

    @Test
    void rejectsLatestStateRequestWhenRequesterCannotAccessConversation() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(11L));
        when(stateService.hasConversationAccess(910L, 11L)).thenReturn(false);

        ConversationController controller = new ConversationController(stateService, sessionService);
        var response = controller.latestState(910L, "session-ok");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
        verify(sessionService).resolveUserId("session-ok");
        verify(stateService).hasConversationAccess(910L, 11L);
        verify(stateService, never()).findConversationLatestState(910L);
    }

    @Test
    void rejectsConversationEndpointsWhenSessionIsInvalid() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        ConversationController controller = new ConversationController(stateService, sessionService);
        var privateResponse = controller.privatePeerLatestReceivedSeq(900L, "expired-session");
        var latestStateResponse = controller.latestState(910L, "expired-session");

        assertEquals(HttpStatus.UNAUTHORIZED, privateResponse.getStatus());
        assertTrue(privateResponse.body() instanceof Map<?, ?>);
        assertEquals(HttpStatus.UNAUTHORIZED, latestStateResponse.getStatus());
        assertTrue(latestStateResponse.body() instanceof Map<?, ?>);
        verify(sessionService, times(2)).resolveUserId("expired-session");
    }
}
