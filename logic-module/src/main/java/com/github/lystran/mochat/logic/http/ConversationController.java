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

@Controller("/conversations")
@Requires(beans = ConversationStateService.class)
public final class ConversationController {
    private final ConversationStateService conversationStateService;
    private final SessionService sessionService;

    public ConversationController(ConversationStateService conversationStateService, SessionService sessionService) {
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Get("/{conversationId}/private-peer-received-seq")
    public HttpResponse<?> privatePeerLatestReceivedSeq(long conversationId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }

        return conversationStateService.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid.get())
            .map(seq -> HttpResponse.ok(new PrivatePeerLatestReceivedSeqResponse(conversationId, seq)))
            .orElseGet(HttpResponse::notFound);
    }

    @Get("/{conversationId}/state")
    public HttpResponse<?> latestState(long conversationId, @QueryValue String sessionId) {
        var requesterUid = sessionService.resolveUserId(sessionId);
        if (requesterUid.isEmpty()) {
            return HttpResponse.unauthorized().body(Map.of("error", "invalid session"));
        }
        if (!conversationStateService.hasConversationAccess(conversationId, requesterUid.get())) {
            return HttpResponse.notFound();
        }

        return conversationStateService.findConversationLatestState(conversationId)
            .map(state -> HttpResponse.ok(new ConversationLatestStateResponse(
                state.conversationId(),
                state.latestSeq(),
                state.latestMessageTime()
            )))
            .orElseGet(HttpResponse::notFound);
    }

    public record PrivatePeerLatestReceivedSeqResponse(long conversationId, long latestReceivedSeq) {
    }

    public record ConversationLatestStateResponse(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
