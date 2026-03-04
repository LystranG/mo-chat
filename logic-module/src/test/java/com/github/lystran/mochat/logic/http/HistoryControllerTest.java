package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.HistoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryControllerTest {
    @Test
    void defaultsLimitToFiftyWhenNotProvided() {
        HistoryService historyService = mock(HistoryService.class);
        when(historyService.query(88L, null, 50)).thenReturn(
            List.of(new HistoryService.HistoryMessage(10L, 101L, 1234L, "payload"))
        );

        HistoryController controller = new HistoryController(historyService);
        HistoryController.HistoryResponse response = controller.history(88L, null, null);

        assertEquals(1, response.items().size());
        assertEquals(10L, response.items().getFirst().seq());
        verify(historyService).query(88L, null, 50);
    }

    @Test
    void usesCursorAndLimitWindowFromRequest() {
        HistoryService historyService = mock(HistoryService.class);
        when(historyService.query(88L, 120L, 20)).thenReturn(
            List.of(new HistoryService.HistoryMessage(119L, 201L, 4567L, "next"))
        );

        HistoryController controller = new HistoryController(historyService);
        HistoryController.HistoryResponse response = controller.history(88L, 120L, 20);

        assertEquals(1, response.items().size());
        assertEquals(119L, response.items().getFirst().seq());
        verify(historyService).query(88L, 120L, 20);
    }
}
