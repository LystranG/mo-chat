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
 * 提供历史消息查询入口，既支持“从某条消息之前继续查”，也支持“直接查一段消息编号”。
 */
@Controller("/history")
@Requires(beans = HistoryService.class)
public final class HistoryController {
    private final HistoryService historyService;
    private final ConversationStateService conversationStateService;
    private final SessionService sessionService;

    /**
     * 收下历史查询接口要用到的业务组件。
     */
    public HistoryController(
        HistoryService historyService,
        ConversationStateService conversationStateService,
        SessionService sessionService
    ) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /**
     * 兼容只传 `cursorSeq` 的旧调用方式，表示“从某条消息之前继续往前查”。
     */
    public HttpResponse<?> history(@QueryValue String sessionId, long conversationId, @Nullable Long cursorSeq, @Nullable @QueryValue Integer limit) {
        return history(sessionId, conversationId, cursorSeq, null, null, limit);
    }

    /**
     * 查询历史消息，并在真正查库前先校验 session、访问权限和参数组合是否正确。
     */
    @Get
    public HttpResponse<?> history(
        @QueryValue String sessionId,
        long conversationId,
        @Nullable @QueryValue Long cursorSeq,
        @Nullable @QueryValue Long startSeq,
        @Nullable @QueryValue Long endSeq,
        @Nullable @QueryValue Integer limit
    ) {
        // sessionId 只是 HTTP 参数，真正判断它是不是当前有效登录由 SessionService 负责。
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        // 先确认这个人有权看这段会话，再继续查历史消息。
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        // 这几个参数只能选一种查法：
        // 要么给 `cursorSeq`，表示“从这条消息之前继续往前查”；
        // 要么同时给 `startSeq/endSeq`，表示“只看这两条编号之间的消息”。
        if (cursorSeq != null && (startSeq != null || endSeq != null)) {
            return HttpResponse.badRequest(Map.of("error", "cursorSeq is mutually exclusive with startSeq/endSeq"));
        }
        if ((startSeq == null) != (endSeq == null)) {
            return HttpResponse.badRequest(Map.of("error", "startSeq and endSeq must be provided together"));
        }
        if (startSeq != null && startSeq > endSeq) {
            return HttpResponse.badRequest(Map.of("error", "startSeq must be <= endSeq"));
        }

        // 没显式传 limit 时走默认页大小，避免一次把整段历史全拉出来。
        int resolvedLimit = limit == null ? HistoryService.DEFAULT_LIMIT : limit;
        // 这里走的是已经整理好的历史查询结果，按会话和编号直接取，不在这个接口里重算消息。
        List<HistoryItem> items = historyService.query(conversationId, cursorSeq, startSeq, endSeq, resolvedLimit)
            .stream()
            .map(message -> new HistoryItem(message.seq(), message.msgId(), message.serverTimeMs(), message.payloadBase64()))
            .toList();
        return HttpResponse.ok(new HistoryResponse(items));
    }

    /** 历史消息列表返回体。 */
    public record HistoryResponse(List<HistoryItem> items) {
    }

    /** 单条历史消息返回体，`payloadBase64` 是为了让二进制消息内容能安全放进 JSON。 */
    public record HistoryItem(long seq, long msgId, long serverTimeMs, String payloadBase64) {
    }
}
