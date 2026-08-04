package com.mochi_753.steadychunks.diagnostics.inflight;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定容量（32768）在途任务事件环形缓冲。
 * <p>
 * 容量恒定 → 内存有界（primitive 数组，总计约 1.5MB）；满时覆盖最旧事件。
 * <p>
 * 审查 P1 修复：<b>写路径无锁</b>——写入槽位由 {@link #writeSequence} 原子
 * 递增决定（{@code slot = sequence & (CAPACITY-1)}），各事件字段写入独立
 * primitive 数组槽位，不创建堆对象、无监视器争用（worldgen 线程并发写安全，
 * 槽位互不重叠；快照读容忍轻微不一致——诊断数据不要求严格一致）。
 */
public final class TaskTraceRingBuffer {
    /** 固定容量：32768 条事件（阶段 3 规格；2 的幂，槽位用掩码） */
    public static final int CAPACITY = 32768;
    private static final int MASK = CAPACITY - 1;

    private final long[] taskIds = new long[CAPACITY];
    private final long[] nanoTimes = new long[CAPACITY];
    private final long[] threadIds = new long[CAPACITY];
    private final int[] eventTypes = new int[CAPACITY];
    private final int[] dimensionIds = new int[CAPACITY];
    private final int[] chunkXs = new int[CAPACITY];
    private final int[] chunkZs = new int[CAPACITY];
    /** 总写入序列（含被覆盖的）；槽位 = sequence & MASK */
    private final AtomicLong writeSequence = new AtomicLong();

    public void record(TaskTraceEvent event) {
        long sequence = writeSequence.getAndIncrement();
        int slot = (int) (sequence & MASK);
        taskIds[slot] = event.taskId();
        nanoTimes[slot] = event.nanoTime();
        threadIds[slot] = event.threadId();
        eventTypes[slot] = event.type().ordinal();
        dimensionIds[slot] = event.dimensionId();
        chunkXs[slot] = event.chunkX();
        chunkZs[slot] = event.chunkZ();
    }

    /** 当前快照（按写入顺序，最旧在前；最多 CAPACITY 条；无锁读，容忍轻微不一致）。 */
    public List<TaskTraceEvent> snapshot() {
        long sequence = writeSequence.get();
        int count = (int) Math.min(sequence, CAPACITY);
        List<TaskTraceEvent> out = new ArrayList<>(count);
        long base = Math.max(0, sequence - CAPACITY);
        // 写入序列单调递增且槽位 = seq & MASK：seq ∈ [base, base+count) 的槽位必然
        // 已写入（sequence < CAPACITY 时是 0..sequence-1；否则全槽位被环形覆盖过）
        for (long seq = base; seq < base + count; seq++) {
            int slot = (int) (seq & MASK);
            out.add(new TaskTraceEvent(
                    taskIds[slot],
                    TaskEventType.values()[eventTypes[slot]],
                    nanoTimes[slot],
                    threadIds[slot],
                    dimensionIds[slot],
                    chunkXs[slot],
                    chunkZs[slot]));
        }
        return out;
    }

    /** 总写入事件数（含已被覆盖的）。 */
    public long totalRecorded() {
        return writeSequence.get();
    }

    /** 当前保留事件数（≤ CAPACITY）。 */
    public int size() {
        return (int) Math.min(writeSequence.get(), CAPACITY);
    }

    public void clear() {
        writeSequence.set(0);
        java.util.Arrays.fill(taskIds, 0);
        java.util.Arrays.fill(nanoTimes, 0);
        java.util.Arrays.fill(threadIds, 0);
        java.util.Arrays.fill(eventTypes, 0);
        java.util.Arrays.fill(dimensionIds, 0);
        java.util.Arrays.fill(chunkXs, 0);
        java.util.Arrays.fill(chunkZs, 0);
    }
}
