package com.github.lystran.mochat.logic.chat;

import java.util.Map;

/**
 * 负责把已经接受的消息投递给在线用户。
 */
public interface MessageRecipientDispatcher {
    /**
     * 把一条私聊消息发给接收方当前在线的连接。
     */
    MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery);

    /**
     * 把一条群消息分别发给每个在线群成员。
     */
    Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery);
}
