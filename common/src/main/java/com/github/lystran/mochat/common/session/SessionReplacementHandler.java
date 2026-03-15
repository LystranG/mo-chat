package com.github.lystran.mochat.common.session;

/**
 * 在新登录顶掉旧登录时，处理旧路由后续动作。
 */
public interface SessionReplacementHandler {
    /**
     * 处理新登录写路由后发现的旧连接替换问题。
     */
    void handleReplacement(ResolvedSession newBinding, PersistedSessionRoute persistedRoute);

    /**
     * 返回一个什么都不做的处理器。
     */
    static SessionReplacementHandler noop() {
        return (newBinding, persistedRoute) -> {
        };
    }
}
