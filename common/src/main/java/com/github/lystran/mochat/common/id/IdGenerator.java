package com.github.lystran.mochat.common.id;

/**
 * 用来生成全局唯一 ID。
 */
public interface IdGenerator {
    /**
     * 生成下一个唯一 ID。
     */
    long nextId();
}
