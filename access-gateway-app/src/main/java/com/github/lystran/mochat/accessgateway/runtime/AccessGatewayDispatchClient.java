package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.KickConnectionResponse;

/**
 * 给其他网关实例发内部调度命令的轻量客户端。
 */
public interface AccessGatewayDispatchClient {
    /**
     * 请求目标网关把指定旧连接关掉。
     */
    KickConnectionResponse kickConnection(KickConnectionRequest request);
}
