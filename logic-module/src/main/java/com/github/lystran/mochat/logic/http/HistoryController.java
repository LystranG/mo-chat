package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.HistoryService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

import java.util.List;
import java.util.Objects;

@Controller("/history")
@Requires(beans = HistoryService.class)
public final class HistoryController {
    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
    }

    @Get
    public HistoryResponse history(long conversationId, @Nullable Long cursorSeq, @Nullable @QueryValue Integer limit) {
        int resolvedLimit = limit == null ? HistoryService.DEFAULT_LIMIT : limit;
        List<HistoryItem> items = historyService.query(conversationId, cursorSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryItem(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
        return new HistoryResponse(items);
    }

    public record HistoryResponse(List<HistoryItem> items) {
    }

    public record HistoryItem(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
