package com.github.lystran.mochat.messageservice.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;

/**
 * 用雪花算法生成 message-service 里的全局消息 ID。
 */
public final class MessageServiceSnowflakeIdGenerator implements IdGenerator {
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L;
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence;

    /**
     * 按 workerId 创建一个发号器。
     */
    public MessageServiceSnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一消息 ID。
     */
    @Override
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("system clock moved backwards");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0L) {
                // 同一毫秒内序号用完时，等下一毫秒再继续发号，避免重复。
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_SHIFT)
            | (workerId << WORKER_ID_SHIFT)
            | sequence;
    }

    /**
     * 等系统时间走到下一毫秒。
     */
    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
