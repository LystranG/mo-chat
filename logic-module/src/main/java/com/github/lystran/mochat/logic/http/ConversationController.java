package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.ConversationStateService;
import com.github.lystran.mochat.logic.service.SessionService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

import java.util.Map;
import java.util.Objects;

/**
 * 提供会话相关的读接口，比如对方已经确认到哪条消息、这段会话目前推进到哪条消息。
 */
@Controller("/conversations")
@Requires(beans = ConversationStateService.class)
public final class ConversationController {
    private final ConversationStateService conversationStateService;
    private final SessionService sessionService;

    /**
     * 收下会话读接口要用到的业务组件。
     */
    public ConversationController(ConversationStateService conversationStateService, SessionService sessionService) {
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /**
     * 查询私聊里“对方已经确认收到哪条消息”。
     */
    @Get("/{conversationId}/private-peer-received-seq")
    public HttpResponse<?> privatePeerLatestReceivedSeq(long conversationId, @QueryValue String sessionId) {
        // HTTP 只带 sessionId，谁是当前登录人由 SessionService 统一确认。
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }

        // 这里查的是“对方确认收到哪条消息”，不是当前用户自己本地的阅读进度。
        return conversationStateService.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid.get())
            .map(seq -> HttpResponse.ok(new PrivatePeerLatestReceivedSeqResponse(conversationId, seq)))
            .orElseGet(HttpResponse::notFound);
    }

    /**
     * 查询这段会话当前保存到哪里了，比如最新消息编号和时间。
     */
    @Get("/{conversationId}/state")
    public HttpResponse<?> latestState(long conversationId, @QueryValue String sessionId) {
        // 这个 sessionId 不是客户端自己说了算，真正是否有效仍然由 SessionService 判断。
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        // 先确认这个人有权看这段会话，再继续查具体状态。
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        // 这里只读已经整理好的会话状态，不会为了这个接口重新回扫整段消息历史。
        return conversationStateService.findConversationLatestState(conversationId)
            .map(state -> HttpResponse.ok(new ConversationLatestStateResponse(
                state.conversationId(),
                state.latestSeq(),
                state.latestMessageTime()
            )))
            .orElseGet(HttpResponse::notFound);
    }

    /** 私聊对方确认进度的返回体。 */
    public record PrivatePeerLatestReceivedSeqResponse(long conversationId, long latestReceivedSeq) {
    }

    /** 会话最新状态的返回体。 */
    public record ConversationLatestStateResponse(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
