package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.HistoryService;
import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryControllerTest {
    @Test
    void defaultsLimitToFiftyWhenNotProvided() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(conversationStateService.hasConversationAccess(88L, 7L)).thenReturn(true);
        when(historyService.query(88L, null, null, null, 50)).thenReturn(
            List.of(new HistoryService.HistoryMessage(10L, 101L, 7L, 1234L, "payload"))
        );

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, null, null);
        HistoryController.HistoryResponse body = (HistoryController.HistoryResponse) response.body();

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(1, body.items().size());
        assertEquals(10L, body.items().getFirst().seq());
        verify(sessionService).resolveUserId("session-ok");
        verify(conversationStateService).hasConversationAccess(88L, 7L);
        verify(historyService).query(88L, null, null, null, 50);
    }

    @Test
    void usesCursorAndLimitWindowFromRequest() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(conversationStateService.hasConversationAccess(88L, 7L)).thenReturn(true);
        when(historyService.query(88L, 120L, null, null, 20)).thenReturn(
            List.of(new HistoryService.HistoryMessage(119L, 201L, 7L, 4567L, "next"))
        );

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, 120L, 20);
        HistoryController.HistoryResponse body = (HistoryController.HistoryResponse) response.body();

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(1, body.items().size());
        assertEquals(119L, body.items().getFirst().seq());
        verify(sessionService).resolveUserId("session-ok");
        verify(conversationStateService).hasConversationAccess(88L, 7L);
        verify(historyService).query(88L, 120L, null, null, 20);
    }

    @Test
    void usesStartAndEndSeqWindowFromRequest() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(conversationStateService.hasConversationAccess(88L, 7L)).thenReturn(true);
        when(historyService.query(88L, null, 101L, 120L, 20)).thenReturn(
            List.of(new HistoryService.HistoryMessage(120L, 301L, 7L, 5678L, "range"))
        );

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, null, 101L, 120L, 20);
        HistoryController.HistoryResponse body = (HistoryController.HistoryResponse) response.body();

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(1, body.items().size());
        assertEquals(120L, body.items().getFirst().seq());
        verify(sessionService).resolveUserId("session-ok");
        verify(conversationStateService).hasConversationAccess(88L, 7L);
        verify(historyService).query(88L, null, 101L, 120L, 20);
    }

    @Test
    void rejectsMutuallyExclusiveCursorAndRangeParameters() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(conversationStateService.hasConversationAccess(88L, 7L)).thenReturn(true);

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, 120L, 101L, 120L, 20);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        assertEquals(
            "cursorSeq is mutually exclusive with startSeq/endSeq",
            ((Map<?, ?>) response.body()).get("error")
        );
        verify(sessionService).resolveUserId("session-ok");
        verify(conversationStateService).hasConversationAccess(88L, 7L);
        verify(historyService, never()).query(88L, 120L, 101L, 120L, 20);
    }

    @Test
    void rejectsHistoryRequestWhenSessionIsInvalid() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("expired-session", 88L, null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("expired-session");
        verify(conversationStateService, never()).hasConversationAccess(anyLong(), anyLong());
    }

    @Test
    void rejectsHistoryRequestWhenRequesterCannotAccessConversation() {
        HistoryService historyService = mock(HistoryService.class);
        ConversationStateService conversationStateService = mock(ConversationStateService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(conversationStateService.hasConversationAccess(88L, 7L)).thenReturn(false);

        HistoryController controller = new HistoryController(historyService, conversationStateService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
        verify(sessionService).resolveUserId("session-ok");
        verify(conversationStateService).hasConversationAccess(88L, 7L);
        verify(historyService, never()).query(88L, null, null, null, 50);
    }
}
