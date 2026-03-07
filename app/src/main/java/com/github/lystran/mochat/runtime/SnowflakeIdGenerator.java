package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.common.id.IdGenerator;

public final class SnowflakeIdGenerator implements IdGenerator {
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

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

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

    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
