package com.mochi_753.steadychunks.diagnostics.inflight;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单任务在途聚合状态（注册表活动表条目，可变值类型）。
 * <p>
 * 只记录任务身份与最近状态变化（时间/线程/类型/坐标），不持有任务对象——
 * 任务终态后条目即被移除，不阻碍回收。{@code terminal} 用于每 taskId 只允许
 * 一次 TASK_TERMINAL 的强制（CAS）。
 */
public final class InflightTaskRecord {
    public final long taskId;
    public final long createdNanos;
    public volatile TaskEventType lastType;
    public volatile long lastNanos;
    public volatile long lastThreadId;
    /** 首次事件的维度 id / 区块坐标（后续事件不再覆盖，保留第一现场） */
    public volatile int dimensionId = -1;
    public volatile int chunkX = Integer.MIN_VALUE;
    public volatile int chunkZ = Integer.MIN_VALUE;
    final AtomicBoolean terminal = new AtomicBoolean(false);

    public InflightTaskRecord(long taskId, long createdNanos) {
        this.taskId = taskId;
        this.createdNanos = createdNanos;
        this.lastType = TaskEventType.CREATED;
        this.lastNanos = createdNanos;
    }
}
