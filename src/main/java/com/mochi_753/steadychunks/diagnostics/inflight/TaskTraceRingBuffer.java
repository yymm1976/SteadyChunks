package com.mochi_753.steadychunks.diagnostics.inflight;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定容量（32768）在途任务事件环形缓冲。
 * <p>
 * 容量恒定 → 内存有界（primitive 数组，总计约 1.5MB）；满时覆盖最旧事件。
 * <p>
 * 审查 P1 修复：<b>写路径无锁且零堆分配</b>——写入槽位由 {@link #writeSequence}
 * 原子递增决定（{@code slot = sequence & (CAPACITY-1)}），各事件字段直接写入
 * 独立 primitive 数组槽位（调用方传字段，不构造事件对象），无监视器争用
 * （worldgen 线程并发写安全，槽位互不重叠）。
 * <p>
 * 审查 P1（第 2 轮）修复：<b>快照撕裂防护</b>——{@link #publishedSequences}
 * 每槽位发布序号（VarHandle release/acquire 语义）：写路径先置 WRITING_MARKER
 * 再写字段、最后发布真实序号；读路径读字段前后各取一次发布序号，不一致（写中
 * 或被覆盖）则跳过该槽位——诊断数据只容忍"缺失"，不容忍"错配字段拼装"。
 */
public final class TaskTraceRingBuffer {
    /** 固定容量：32768 条事件（阶段 3 规格；2 的幂，槽位用掩码） */
    public static final int CAPACITY = 32768;
    private static final int MASK = CAPACITY - 1;
    /** 槽位写入中的标记（快照读到该值则跳过；sequence 从 0 起，永不等于 -1） */
    private static final long WRITING_MARKER = -1L;

    private final long[] taskIds = new long[CAPACITY];
    private final long[] nanoTimes = new long[CAPACITY];
    private final long[] threadIds = new long[CAPACITY];
    private final int[] eventTypes = new int[CAPACITY];
    private final int[] dimensionIds = new int[CAPACITY];
    private final int[] chunkXs = new int[CAPACITY];
    private final int[] chunkZs = new int[CAPACITY];
    /** 每槽位发布序号：= 写入该槽位的 sequence（release 语义，先于字段可见） */
    private final long[] publishedSequences = new long[CAPACITY];
    /** 总写入序列（含被覆盖的）；槽位 = sequence & MASK */
    private final AtomicLong writeSequence = new AtomicLong();

    private static final VarHandle PUBLISHED;
    static {
        try {
            PUBLISHED = MethodHandles.arrayElementVarHandle(long[].class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 审查 P1（第 3 轮）修复：发布序号数组构造时即复位为 WRITING_MARKER——
     * 默认全 0 而 0 是首条合法事件的 sequence，首条写入的
     * getAndIncrement 与写字段之间快照会把全零槽位当成伪事件 0。
     */
    public TaskTraceRingBuffer() {
        java.util.Arrays.fill(publishedSequences, WRITING_MARKER);
    }

    /** 零堆分配写入：调用方传字段，本方法不构造任何对象。 */
    public void record(long taskId, TaskEventType type, long nanoTime, long threadId,
                       int dimensionId, int chunkX, int chunkZ) {
        long sequence = writeSequence.getAndIncrement();
        int slot = (int) (sequence & MASK);
        PUBLISHED.setRelease(publishedSequences, slot, WRITING_MARKER);
        taskIds[slot] = taskId;
        nanoTimes[slot] = nanoTime;
        threadIds[slot] = threadId;
        eventTypes[slot] = type.ordinal();
        dimensionIds[slot] = dimensionId;
        chunkXs[slot] = chunkX;
        chunkZs[slot] = chunkZ;
        PUBLISHED.setRelease(publishedSequences, slot, sequence);
    }

    /** 当前快照（按写入顺序，最旧在前；最多 CAPACITY 条；无锁读，容忍缺失）。 */
    public List<TaskTraceEvent> snapshot() {
        long sequence = writeSequence.get();
        int count = (int) Math.min(sequence, CAPACITY);
        List<TaskTraceEvent> out = new ArrayList<>(count);
        long base = Math.max(0, sequence - CAPACITY);
        // 写入序列单调递增且槽位 = seq & MASK：seq ∈ [base, base+count) 的槽位必然
        // 已写入（sequence < CAPACITY 时是 0..sequence-1；否则全槽位被环形覆盖过）
        for (long seq = base; seq < base + count; seq++) {
            int slot = (int) (seq & MASK);
            // 读前取发布序号：写中（WRITING_MARKER）或已被更高序列覆盖 → 跳过
            if ((long) PUBLISHED.getAcquire(publishedSequences, slot) != seq) {
                continue;
            }
            long taskId = taskIds[slot];
            long nanoTime = nanoTimes[slot];
            long threadId = threadIds[slot];
            int typeOrdinal = eventTypes[slot];
            int dimensionId = dimensionIds[slot];
            int chunkX = chunkXs[slot];
            int chunkZ = chunkZs[slot];
            // 读后复查：读字段期间该槽位被覆盖/重写 → 丢弃（防字段错配拼装）
            if ((long) PUBLISHED.getAcquire(publishedSequences, slot) != seq) {
                continue;
            }
            out.add(new TaskTraceEvent(taskId, TaskEventType.values()[typeOrdinal],
                    nanoTime, threadId, dimensionId, chunkX, chunkZ));
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
        // 发布序号复位为 WRITING_MARKER（非 0）：0 是合法发布序列（首条事件），
        // 未写槽位必须与任何真实序列可区分，否则快照会读到 clear 后的伪事件
        java.util.Arrays.fill(publishedSequences, WRITING_MARKER);
        java.util.Arrays.fill(taskIds, 0);
        java.util.Arrays.fill(nanoTimes, 0);
        java.util.Arrays.fill(threadIds, 0);
        java.util.Arrays.fill(eventTypes, 0);
        java.util.Arrays.fill(dimensionIds, 0);
        java.util.Arrays.fill(chunkXs, 0);
        java.util.Arrays.fill(chunkZs, 0);
    }
}
