package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.ConversationStateService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

import java.util.Objects;

@Controller("/conversations")
@Requires(beans = ConversationStateService.class)
public final class ConversationController {
    private final ConversationStateService conversationStateService;

    public ConversationController(ConversationStateService conversationStateService) {
        this.conversationStateService = Objects.requireNonNull(conversationStateService, "conversationStateService");
    }

    @Get("/{conversationId}/private-peer-received-seq")
    public HttpResponse<PrivatePeerLatestReceivedSeqResponse> privatePeerLatestReceivedSeq(long conversationId, @QueryValue long requesterUid) {
        return conversationStateService.findPrivatePeerLatestReceivedSeq(conversationId, requesterUid)
            .map(seq -> HttpResponse.ok(new PrivatePeerLatestReceivedSeqResponse(conversationId, seq)))
            .orElseGet(HttpResponse::notFound);
    }

    @Get("/{conversationId}/state")
    public HttpResponse<ConversationLatestStateResponse> latestState(long conversationId) {
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
