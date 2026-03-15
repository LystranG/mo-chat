package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.HistoryService;
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

/**
 * 提供会话历史查询入口，既能从某条消息之前继续查，也能按一段消息编号去查。
 */
@Controller("/history")
@Requires(beans = HistoryService.class)
public final class HistoryController {
    private final HistoryService historyService;
    private final ConversationStateService conversationStateService;
    private final SessionService sessionService;

    // 注入历史查询、会话访问控制和 session 鉴权依赖。
    public HistoryController(
        HistoryService historyService,
        ConversationStateService conversationStateService,
        SessionService sessionService
    ) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    // 兼容老调用方：只传“从哪条消息之前继续查”这一种查法。
    public HttpResponse<?> history(@QueryValue String sessionId, long conversationId, @Nullable Long cursorSeq, @Nullable @QueryValue Integer limit) {
        return history(sessionId, conversationId, cursorSeq, null, null, limit);
    }

    @Get
    // 执行历史查询，并在进入 service 前先校验 session 和查法是否合法。
    public HttpResponse<?> history(
        @QueryValue String sessionId,
        long conversationId,
        @Nullable @QueryValue Long cursorSeq,
        @Nullable @QueryValue Long startSeq,
        @Nullable @QueryValue Long endSeq,
        @Nullable @QueryValue Integer limit
    ) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        // /history 只允许二选一：要么表示“从某条消息之前继续查”，要么表示“查 startSeq 到 endSeq 这一段”。
        if (cursorSeq != null && (startSeq != null || endSeq != null)) {
            return HttpResponse.badRequest(Map.of("error", "cursorSeq is mutually exclusive with startSeq/endSeq"));
        }
        if ((startSeq == null) != (endSeq == null)) {
            return HttpResponse.badRequest(Map.of("error", "startSeq and endSeq must be provided together"));
        }
        if (startSeq != null && startSeq > endSeq) {
            return HttpResponse.badRequest(Map.of("error", "startSeq must be <= endSeq"));
        }

        int resolvedLimit = limit == null ? HistoryService.DEFAULT_LIMIT : limit;
        List<HistoryItem> items = historyService.query(conversationId, cursorSeq, startSeq, endSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryItem(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
        return HttpResponse.ok(new HistoryResponse(items));
    }

    /** 历史查询响应体。 */
    public record HistoryResponse(List<HistoryItem> items) {
    }

    /** 单条历史消息响应体。 */
    public record HistoryItem(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
