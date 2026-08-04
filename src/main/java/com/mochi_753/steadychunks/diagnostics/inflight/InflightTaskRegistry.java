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
    /** 审查 P1 修复：被容量逐出的未终态任务集合——后续终态不视为重复异常 */
    private final BoundedIdSet evictedTaskIds = new BoundedIdSet(EVICTED_CAP);
    /** 容量溢出（逐出）计数——事故快照显示诊断容量是否被击穿 */
    private final AtomicLong evictionCount = new AtomicLong();
    /** 审查修复：已终态任务集合（有界）——TERMINAL 之后到达的迟到非终态事件
     * （如 PROXY_COMPLETED 在 STEADY_STAGE_TERMINAL 之后记录）不得把已终态任务
     * 重新激活进活动表（实测：活动表残留 last=PROXY_COMPLETED 永久不消） */
    private final BoundedIdSet terminatedTaskIds = new BoundedIdSet(TERMINATED_CAP);
    private static final int TERMINATED_CAP = 16384;
    /** 审查 P1（第 2 轮）修复：逐出记忆容量（被逐出任务数量有界，防泄漏场景无界增长） */
    private static final int EVICTED_CAP = 16384;

    /**
     * 有界 FIFO 集合：插入序淘汰 + O(1) 判含（诊断记忆，防无界增长）。
     * <p>
     * 审查 P1（第 2 轮）修复：替代粗粒度 clear()（清空使全部已终态任务失忆，
     * 迟到事件窗口内重新激活）——容量超限时只逐出最旧一个，保留最近记忆。
     * <p>
     * 审查 P1（第 3 轮）修复：set 判重后再入 fifo——重复 add 不得产生多个
     * FIFO 节点（否则淘汰其中一个节点会误删整个 set 成员）；remove 以 set
     * 是否真实存在为准，FIFO 只是淘汰辅助。
     */
    private static final class BoundedIdSet {
        private final int capacity;
        private final java.util.concurrent.ConcurrentLinkedQueue<Long> fifo =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.Set<Long> set = ConcurrentHashMap.newKeySet();

        BoundedIdSet(int capacity) {
            this.capacity = capacity;
        }

        void add(long id) {
            // 审查（第 3 轮）修复：set.add 成功（首次加入）才入 FIFO——
            // 并发重复 add 不得产生重复节点
            if (!set.add(id)) {
                return;
            }
            fifo.add(id);
            // 容量超限逐出最旧（set.size() O(1)；每 add 至多需逐出一个，循环兜底并发）
            while (set.size() > capacity) {
                Long oldest = fifo.poll();
                if (oldest == null) {
                    break;
                }
                set.remove(oldest);
            }
        }

        boolean contains(long id) {
            return set.contains(id);
        }

        boolean remove(long id) {
            // 审查（第 3 轮）修复：以 set 是否真实存在为准；FIFO.remove 只做
            // 淘汰辅助（O(n) 低频），返回值不用于判定
            boolean existed = set.remove(id);
            if (existed) {
                fifo.remove(id);
            }
            return existed;
        }

        void clear() {
            fifo.clear();
            set.clear();
        }
    }

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
        // 审查 P1（第 2 轮）修复：零堆分配——直接传字段入环，不构造 TaskTraceEvent
        ring.record(taskId, type, now, threadId, dimensionId, chunkX, chunkZ);

        if (type == TaskEventType.STEADY_STAGE_TERMINAL) {
            InflightTaskRecord record = active.get(taskId);
            if (record != null && record.terminal.compareAndSet(false, true)) {
                record.lastType = type;
                record.lastNanos = now;
                record.lastThreadId = threadId;
                // 审查（第 3 轮非阻断 P1）修复：先发布终态记忆、再移除 active——
                // 旧顺序（先 remove 后 add）下，迟到非终态事件可能落在
                // "active 已空、terminated 未登记"之间，computeIfAbsent 重建记录
                // 且二次检查仍看不到终态标记（zombie active）。先 add 再
                // remove(key, record)：并发迟到事件的二次检查必然命中终态标记
                terminatedTaskIds.add(taskId);
                active.remove(taskId, record);
            } else if (evictedTaskIds.remove(taskId)) {
                // 审查 P1 修复：任务曾被容量逐出（未终态即出表）——终态正常，
                // 不视为重复异常；从逐出集合移除（释放跟踪）
            } else {
                // 重复终态或未知任务：终态唯一性被破坏
                terminalAnomalies.incrementAndGet();
            }
            return;
        }

        // 审查修复：已终态任务的迟到非终态事件——直接忽略（不入活动表、不入环
        // 已入——事件本身保留在环中供诊断，仅不激活活动条目）
        if (active.get(taskId) == null && terminatedTaskIds.contains(taskId)) {
            return;
        }

        InflightTaskRecord record = active.computeIfAbsent(taskId, id -> new InflightTaskRecord(id, now));
        // 审查（第 3 轮）修复：computeIfAbsent 与终态移除之间的并发窗口——
        // 终态线程可能刚移除记录，迟到事件随后重新创建；更新后复查终止记忆，
        // 已终态则条件移除（remove(key, value) 只移除本线程创建的记录）
        if (terminatedTaskIds.contains(taskId)) {
            active.remove(taskId, record);
            return;
        }
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
        // 审查（第 3 轮）修复：remove 返回值检查所有权——并发下任务可能已被
        // 终态线程移除，只有真正取走才登记逐出（否则把已终态任务误登记为逐出）
        InflightTaskRecord removed = victim >= 0 ? active.remove(victim) : null;
        if (removed != null) {
            // 审查 P1 修复：逐出必须显式登记——后续终态不计为重复异常，
            // 事故快照可显示诊断容量被击穿（最老任务恰是最可能真卡住的）
            evictedTaskIds.add(victim);
            // 审查 P1（第 2 轮）修复：rememberTerminated——被逐出任务的迟到
            // 非终态事件（EXECUTING/PROXY_COMPLETED 等）不得重新激活进活动表
            // （同已终态语义：诊断放弃跟踪即视为不再活跃）；其真实终态仍走
            // evictedTaskIds.remove 分支，不误计重复异常
            terminatedTaskIds.add(victim);
            evictionCount.incrementAndGet();
        }
    }

    /** 活动（未终态）任务数——停滞检测与清洁断言用。 */
    public int activeTaskCount() {
        return active.size();
    }

    /** 终态唯一性异常计数（重复 STEADY_STAGE_TERMINAL）。 */
    public long terminalAnomalyCount() {
        return terminalAnomalies.get();
    }

    /** 审查 P1 修复：容量溢出（未终态任务被逐出）计数——事故快照显示诊断容量击穿。 */
    public long evictionCount() {
        return evictionCount.get();
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
        evictedTaskIds.clear();
        terminatedTaskIds.clear();
        terminalAnomalies.set(0);
        evictionCount.set(0);
    }
}
