package com.github.lystran.mochat.logic.chat;

import java.util.Map;

public interface MessageRecipientDispatcher {
    MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery);

    Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery);
}
