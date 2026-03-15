package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;

/**
 * 按 Snowflake 规则生成全局唯一消息 ID。
 */
public final class SnowflakeIdGenerator implements IdGenerator {
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L;
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId;

    // 记住上一次发号时的毫秒时间，方便判断是不是还在同一毫秒里继续发号。
    private long lastTimestamp = -1L;
    // 记住当前这一毫秒里已经用到第几个序号。
    private long sequence;

    /**
     * 校验并保存当前节点编号。
     */
    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一 ID；如果还在同一毫秒里，就靠递增序号避免撞号。
     */
    @Override
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("system clock moved backwards");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        return ((timestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_SHIFT)
            | (workerId << WORKER_ID_SHIFT)
            | sequence;
    }

    /**
     * 如果这一毫秒里的序号用完了，就等到下一毫秒再继续发号。
     */
    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 读取当前系统时间的毫秒值。
     */
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
