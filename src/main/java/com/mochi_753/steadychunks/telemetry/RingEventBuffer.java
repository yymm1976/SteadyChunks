package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 环形事件缓冲，无锁单写多读，用于尖峰前后保留完整事件。
 * <p>
 * 设计要点（参考计划 §2 风险缓解"环形缓冲、采样、LongAdder"）：
 * <ul>
 *   <li>固定容量，幂等索引推进，溢出时覆盖最旧事件</li>
 *   <li>写入仅持有一个 CAS（{@code nextSlot}），读取时快照复制</li>
 *   <li>{@link LongAdder} 聚合计数，避免热点竞争</li>
 *   <li>不存储大对象，仅存 {@code long} 编码的时间戳/ID，控制内存占用</li>
 * </ul>
 * 当诊断关闭时，{@link #record} 应被调用方通过 {@code if (enabled)} 短路，本类不重复判断。
 */
public final class RingEventBuffer {
    /** 每条事件：[0]=时间戳纳秒，[1]=编码标识（如 ChunkPos.longKey | statusOrdinal << 56） */
    private final long[][] events;
    private final int mask;
    private final AtomicLongArray nextSlot;
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder overflowCount = new LongAdder();

    /**
     * @param capacity 容量，向上取整为 2 的幂
     */
    public RingEventBuffer(int capacity) {
        int cap = Integer.highestOneBit(Math.max(2, capacity) - 1) << 1;
        this.events = new long[cap][2];
        this.mask = cap - 1;
        this.nextSlot = new AtomicLongArray(new long[]{0});
    }

    /**
     * 记录一条事件。无锁，线程安全。
     *
     * @param timestampNanos 单调时钟纳秒
     * @param encodedId      编码标识，由调用方定义语义
     */
    public void record(long timestampNanos, long encodedId) {
        long idx = nextSlot.getAndIncrement(0);
        int slot = (int) (idx & mask);
        events[slot][0] = timestampNanos;
        events[slot][1] = encodedId;
        totalCount.increment();
        if (idx >= events.length) {
            overflowCount.increment();
        }
    }

    /**
     * 快照当前缓冲内容，按写入顺序返回。读取期间不阻塞写入，可能读到部分最新值。
     */
    public long[][] snapshot() {
        long end = nextSlot.get(0);
        int len = (int) Math.min(events.length, end);
        long[][] copy = new long[len][2];
        long start = end - len;
        for (int i = 0; i < len; i++) {
            int slot = (int) ((start + i) & mask);
            copy[i][0] = events[slot][0];
            copy[i][1] = events[slot][1];
        }
        return copy;
    }

    public long totalCount() {
        return totalCount.sum();
    }

    public long overflowCount() {
        return overflowCount.sum();
    }

    public int capacity() {
        return events.length;
    }

    public void reset() {
        nextSlot.set(0, 0);
        totalCount.reset();
        overflowCount.reset();
    }
}
