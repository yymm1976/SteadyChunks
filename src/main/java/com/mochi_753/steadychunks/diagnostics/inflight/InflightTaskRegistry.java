package com.mochi_753.steadychunks.diagnostics.inflight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在途任务追踪注册表：taskId 分配 + 事件入环 + 活动任务状态 + 终态唯一性强制。
 * <p>
 * 活动表有界（超过 {@link #ACTIVE_CAP} 逐出最早创建的任务——诊断保底，防异常
 * 泄漏场景下无界增长）；终态强制：同一 taskId 第二次 TASK_TERMINAL 被丢弃并
 * 计入 {@link #terminalAnomalyCount()}（重复终态路径的信号，如恢复与取消并发
 * 完成同一任务）。
 */
public final class InflightTaskRegistry {
    /** 活动任务表容量上限（超过逐出最早创建者，防泄漏场景无界增长） */
    public static final int ACTIVE_CAP = 8192;

    private final TaskTraceRingBuffer ring;
    private final AtomicLong taskIdSource = new AtomicLong(1);
    private final ConcurrentHashMap<Long, InflightTaskRecord> active = new ConcurrentHashMap<>();
    private final AtomicLong terminalAnomalies = new AtomicLong();

    public InflightTaskRegistry(TaskTraceRingBuffer ring) {
        this.ring = ring;
    }

    public long allocateTaskId() {
        return taskIdSource.getAndIncrement();
    }

    /**
     * 记录事件：先入环（值类型，可覆盖），再更新活动表。
     * <p>
     * 终态事件：CAS 置位后移除活动条目；重复终态/未知任务计为异常。
     * 非终态事件：创建或更新活动条目；首个携带非哨兵坐标的事件写入任务坐标
     * （后续事件不覆盖——诊断聚焦任务"第一现场"）。
     */
    public void record(long taskId, TaskEventType type, int dimensionId, int chunkX, int chunkZ) {
        long now = System.nanoTime();
        long threadId = Thread.currentThread().threadId();
        ring.record(new TaskTraceEvent(taskId, type, now, threadId, dimensionId, chunkX, chunkZ));

        if (type == TaskEventType.TASK_TERMINAL) {
            InflightTaskRecord record = active.get(taskId);
            if (record != null && record.terminal.compareAndSet(false, true)) {
                record.lastType = type;
                record.lastNanos = now;
                record.lastThreadId = threadId;
                active.remove(taskId);
            } else {
                // 重复终态或未知任务：终态唯一性被破坏
                terminalAnomalies.incrementAndGet();
            }
            return;
        }

        InflightTaskRecord record = active.computeIfAbsent(taskId, id -> new InflightTaskRecord(id, now));
        record.lastType = type;
        record.lastNanos = now;
        record.lastThreadId = threadId;
        if (record.dimensionId < 0 && dimensionId >= 0) {
            record.dimensionId = dimensionId;
            record.chunkX = chunkX;
            record.chunkZ = chunkZ;
        }
        if (active.size() > ACTIVE_CAP) {
            evictOldest();
        }
    }

    private void evictOldest() {
        long oldest = Long.MAX_VALUE;
        long victim = -1;
        for (Map.Entry<Long, InflightTaskRecord> entry : active.entrySet()) {
            if (entry.getValue().createdNanos < oldest) {
                oldest = entry.getValue().createdNanos;
                victim = entry.getKey();
            }
        }
        if (victim >= 0) {
            active.remove(victim);
        }
    }

    /** 活动（未终态）任务数——停滞检测与清洁断言用。 */
    public int activeTaskCount() {
        return active.size();
    }

    /** 终态唯一性异常计数（重复 TASK_TERMINAL）。 */
    public long terminalAnomalyCount() {
        return terminalAnomalies.get();
    }

    public long totalRecorded() {
        return ring.totalRecorded();
    }

    public List<TaskTraceEvent> ringSnapshot() {
        return ring.snapshot();
    }

    /** 活动任务快照（按创建时间升序）。 */
    public List<InflightTaskRecord> activeSnapshot() {
        List<InflightTaskRecord> out = new ArrayList<>(active.values());
        out.sort(Comparator.comparingLong(r -> r.createdNanos));
        return out;
    }

    /** 测试/生命周期复位：清空事件与活动表（计数一并归零）。 */
    public void reset() {
        ring.clear();
        active.clear();
        terminalAnomalies.set(0);
    }
}
