package org.example.springboot.config;

import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdWorker {
    // 起始时间戳 (2024-01-01 00:00:00)
    private final long twepoch = 1704067200000L;
    // 机器ID所占位数
    private final long workerIdBits = 5L;
    // 数据中心ID所占位数
    private final long datacenterIdBits = 5L;
    // 支持的最大机器ID
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);
    // 支持的最大数据中心ID
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);
    // 序列号所占位数
    private final long sequenceBits = 12L;
    // 机器ID左移位数
    private final long workerIdShift = sequenceBits;
    // 数据中心ID左移位数
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    // 时间戳左移位数
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    // 序列号最大值 (4095)
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);

    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker() {
        // 默认 workerId=1, datacenterId=1，实际生产可从配置读取
        this(1, 1);
    }

    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(String.format("workerId 不能大于 %d 或小于 0", maxWorkerId));
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException(String.format("datacenterId 不能大于 %d 或小于 0", maxDatacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = timeGen();
        // 时钟回拨处理（简单场景下直接抛异常）
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨，拒绝生成ID");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - twepoch) << timestampLeftShift) |
                (datacenterId << datacenterIdShift) |
                (workerId << workerIdShift) |
                sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}