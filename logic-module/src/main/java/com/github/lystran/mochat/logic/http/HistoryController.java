package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.HistoryService;
import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller("/history")
@Requires(beans = HistoryService.class)
public final class HistoryController {
    private final HistoryService historyService;
    private final ConversationStateService conversationStateService;
    private final SessionService sessionService;

    public HistoryController(
        HistoryService historyService,
        ConversationStateService conversationStateService,
        SessionService sessionService
    ) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Get
    public HttpResponse<?> history(@QueryValue String sessionId, long conversationId, @Nullable Long cursorSeq, @Nullable @QueryValue Integer limit) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        int resolvedLimit = limit == null ? HistoryService.DEFAULT_LIMIT : limit;
        List<HistoryItem> items = historyService.query(conversationId, cursorSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryItem(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
        return HttpResponse.ok(new HistoryResponse(items));
    }

    public record HistoryResponse(List<HistoryItem> items) {
    }

    public record HistoryItem(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
