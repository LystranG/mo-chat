package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;

/**
 * 按雪花算法生成全局唯一消息 id。
 */
public final class SnowflakeIdGenerator implements IdGenerator {
    private static final long CUSTOM_EPOCH_MILLIS = 1_704_067_200_000L; // 自定义起点时间，能让生成出来的数字更短一些。
    private static final int WORKER_ID_BITS = 10; // 给节点编号预留 10 位，便于多实例并行生成。
    private static final int SEQUENCE_BITS = 12; // 同一毫秒内最多支持 4096 个连续编号。
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId; // 当前实例的节点编号。

    private long lastTimestamp = -1L; // 上一次成功发号时的毫秒值。
    private long sequence; // 同一毫秒里已经用到的序号。

    /**
     * 按节点编号创建发号器。
     */
    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一 id。
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
                // 这一毫秒已经用满时，等到下一毫秒再继续发号。
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
     * 忙等到下一毫秒，避免同一毫秒里的序号溢出。
     */
    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 读取当前系统时间。
     */
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
