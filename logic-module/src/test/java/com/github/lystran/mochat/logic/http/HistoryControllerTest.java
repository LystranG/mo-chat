package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.HistoryService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryControllerTest {
    @Test
    void defaultsLimitToFiftyWhenNotProvided() {
        HistoryService historyService = mock(HistoryService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(historyService.query(88L, null, 50)).thenReturn(
            List.of(new HistoryService.HistoryMessage(10L, 101L, 1234L, "payload"))
        );

        HistoryController controller = new HistoryController(historyService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, null, null);
        HistoryController.HistoryResponse body = (HistoryController.HistoryResponse) response.body();

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(1, body.items().size());
        assertEquals(10L, body.items().getFirst().seq());
        verify(sessionService).resolveUserId("session-ok");
        verify(historyService).query(88L, null, 50);
    }

    @Test
    void usesCursorAndLimitWindowFromRequest() {
        HistoryService historyService = mock(HistoryService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("session-ok")).thenReturn(Optional.of(7L));
        when(historyService.query(88L, 120L, 20)).thenReturn(
            List.of(new HistoryService.HistoryMessage(119L, 201L, 4567L, "next"))
        );

        HistoryController controller = new HistoryController(historyService, sessionService);
        HttpResponse<?> response = controller.history("session-ok", 88L, 120L, 20);
        HistoryController.HistoryResponse body = (HistoryController.HistoryResponse) response.body();

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(1, body.items().size());
        assertEquals(119L, body.items().getFirst().seq());
        verify(sessionService).resolveUserId("session-ok");
        verify(historyService).query(88L, 120L, 20);
    }

    @Test
    void rejectsHistoryRequestWhenSessionIsInvalid() {
        HistoryService historyService = mock(HistoryService.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.resolveUserId("expired-session")).thenReturn(Optional.empty());

        HistoryController controller = new HistoryController(historyService, sessionService);
        HttpResponse<?> response = controller.history("expired-session", 88L, null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body() instanceof Map<?, ?>);
        verify(sessionService).resolveUserId("expired-session");
    }
}
