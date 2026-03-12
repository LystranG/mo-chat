package com.github.lystran.mochat.logic.service;

import com.github.lystran.mochat.logic.repository.MessageRelationshipRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageSendPolicyServiceTest {
    @Test
    void privateMessagingPolicySortsPeersAndAllowsActiveFriendship() {
        StubMessageRelationshipRepository repository = new StubMessageRelationshipRepository();
        repository.privateState = MessageRelationshipRepository.PrivateMessageState.ACTIVE;
        MessageSendPolicyService service = new MessageSendPolicyService(repository);

        MessageSendPolicyService.PrivateMessagingPolicy policy =
            service.checkPrivateMessagingPolicy(200L, 88L, 11L);

        assertEquals(MessageSendPolicyService.PrivateMessagingPolicy.ALLOWED, policy);
        assertEquals(200L, repository.lastConversationId);
        assertEquals(11L, repository.lastPeerUidLow);
        assertEquals(88L, repository.lastPeerUidHigh);
    }

    @Test
    void privateMessagingPolicyReflectsBlockedAndMissingFriendship() {
        StubMessageRelationshipRepository repository = new StubMessageRelationshipRepository();
        MessageSendPolicyService service = new MessageSendPolicyService(repository);

        repository.privateState = MessageRelationshipRepository.PrivateMessageState.NOT_FRIEND;
        assertEquals(
            MessageSendPolicyService.PrivateMessagingPolicy.NOT_FRIEND,
            service.checkPrivateMessagingPolicy(200L, 11L, 88L)
        );

        repository.privateState = MessageRelationshipRepository.PrivateMessageState.BLOCKED;
        assertEquals(
            MessageSendPolicyService.PrivateMessagingPolicy.BLOCKED,
            service.checkPrivateMessagingPolicy(200L, 11L, 88L)
        );
    }

    @Test
    void groupSendContextReturnsGroupNotFoundWhenGroupDoesNotExist() {
        StubMessageRelationshipRepository repository = new StubMessageRelationshipRepository();
        repository.groupExists = false;
        MessageSendPolicyService service = new MessageSendPolicyService(repository);

        MessageSendPolicyService.GroupSendContext context = service.getGroupSendContext(300L, 11L);

        assertEquals(MessageSendPolicyService.GroupSendEligibility.GROUP_NOT_FOUND, context.eligibility());
        assertEquals(List.of(), context.memberUids());
    }

    @Test
    void groupSendContextReturnsNotMemberWhenSenderIsInactive() {
        StubMessageRelationshipRepository repository = new StubMessageRelationshipRepository();
        repository.groupExists = true;
        repository.activeGroupMember = false;
        MessageSendPolicyService service = new MessageSendPolicyService(repository);

        MessageSendPolicyService.GroupSendContext context = service.getGroupSendContext(300L, 11L);

        assertEquals(MessageSendPolicyService.GroupSendEligibility.NOT_MEMBER, context.eligibility());
        assertEquals(List.of(), context.memberUids());
    }

    @Test
    void groupSendContextReturnsAllowedWithActiveMemberIds() {
        StubMessageRelationshipRepository repository = new StubMessageRelationshipRepository();
        repository.groupExists = true;
        repository.activeGroupMember = true;
        repository.groupMembers = List.of(11L, 22L, 33L);
        MessageSendPolicyService service = new MessageSendPolicyService(repository);

        MessageSendPolicyService.GroupSendContext context = service.getGroupSendContext(300L, 11L);

        assertEquals(MessageSendPolicyService.GroupSendEligibility.ALLOWED, context.eligibility());
        assertEquals(List.of(11L, 22L, 33L), context.memberUids());
    }

    private static final class StubMessageRelationshipRepository implements MessageRelationshipRepository {
        private PrivateMessageState privateState = PrivateMessageState.NOT_FRIEND;
        private boolean groupExists;
        private boolean activeGroupMember;
        private List<Long> groupMembers = List.of();
        private long lastConversationId;
        private long lastPeerUidLow;
        private long lastPeerUidHigh;

        @Override
        public PrivateMessageState privateMessageState(long conversationId, long peerUidLow, long peerUidHigh) {
            this.lastConversationId = conversationId;
            this.lastPeerUidLow = peerUidLow;
            this.lastPeerUidHigh = peerUidHigh;
            return privateState;
        }

        @Override
        public boolean groupExists(long groupId) {
            return groupExists;
        }

        @Override
        public boolean isActiveGroupMember(long groupId, long userId) {
            return activeGroupMember;
        }

        @Override
        public List<Long> listActiveGroupMemberIds(long groupId) {
            return groupMembers;
        }
    }
}
