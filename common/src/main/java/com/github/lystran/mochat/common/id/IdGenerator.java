package com.github.lystran.mochat.common.id;

/**
 * 生成全局唯一 id。
 */
public interface IdGenerator {
    /**
     * 取下一个可用 id。
     */
    long nextId();
}
