package com.mochi_753.steadychunks.diagnostics.inflight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 固定容量（32768）在途任务事件环形缓冲。
 * <p>
 * 容量恒定 → 内存有界（每条事件约 40 字节，总计约 1.3MB）；满时覆盖最旧事件
 * （诊断优先关注最近状态变化）。写入同步（事件频率为任务级，无锁竞争问题）；
 * 快照按写入顺序返回（最旧在前），供事故转储与停滞分析。
 */
public final class TaskTraceRingBuffer {
    /** 固定容量：32768 条事件（阶段 3 规格） */
    public static final int CAPACITY = 32768;

    private final TaskTraceEvent[] events = new TaskTraceEvent[CAPACITY];
    private long writeIndex = 0;

    public synchronized void record(TaskTraceEvent event) {
        events[(int) (writeIndex % CAPACITY)] = event;
        writeIndex++;
    }

    /** 当前快照（按写入顺序，最旧在前；最多 CAPACITY 条）。 */
    public synchronized List<TaskTraceEvent> snapshot() {
        int count = (int) Math.min(writeIndex, CAPACITY);
        List<TaskTraceEvent> out = new ArrayList<>(count);
        long base = Math.max(0, writeIndex - CAPACITY);
        for (int i = 0; i < count; i++) {
            TaskTraceEvent e = events[(int) ((base + i) % CAPACITY)];
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** 总写入事件数（含已被覆盖的）。 */
    public synchronized long totalRecorded() {
        return writeIndex;
    }

    /** 当前保留事件数（≤ CAPACITY）。 */
    public synchronized int size() {
        return (int) Math.min(writeIndex, CAPACITY);
    }

    public synchronized void clear() {
        Arrays.fill(events, null);
        writeIndex = 0;
    }
}
