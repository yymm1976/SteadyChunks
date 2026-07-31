package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度器看门狗，对应技术指导 §17.3。
 * <p>
 * 定期扫描任务图，检测异常状态并报告。设计原则：只报告，不自动杀线程，避免误判导致数据损坏。
 * <p>
 * 扫描项（§17.3）：
 * <ul>
 *   <li>QUEUED / WAITING_DEPS / READY 排队过久（基于 {@link ChunkTask#queueEnterNanos}）</li>
 *   <li>RUNNING 执行过久（基于 {@link ChunkTask#stageStartNanos}）</li>
 *   <li>permit 泄漏：inflightCount 与实际 RUNNING 任务数不一致</li>
 *   <li>维度已卸载但任务仍存在（孤儿任务）</li>
 *   <li>依赖链无进展：WAITING_DEPS 状态过久（被排队超时覆盖）</li>
 * </ul>
 * <p>
 * 线程安全：所有可变状态使用并发容器或 volatile，扫描在调度器 tick 线程中调用。
 */
public final class Watchdog {
    private static Watchdog instance;

    /** 已卸载维度集合，用于检测孤儿任务（§17.3 维度已卸载但任务仍存在） */
    private final Set<ResourceKey<Level>> unloadedDimensions = ConcurrentHashMap.newKeySet();
    /** 扫描间隔（tick），默认 200 tick = 10 秒 */
    private volatile int scanIntervalTicks = 200;
    /** 排队超时阈值（毫秒），默认 60 秒 */
    private volatile long queueTimeoutMs = 60_000L;
    /** RUNNING 超时阈值（毫秒），默认 30 秒 */
    private volatile long runningTimeoutMs = 30_000L;
    /** 上次扫描 tick */
    private final AtomicLong lastScanTick = new AtomicLong(0);
    /** 累计扫描次数 */
    private final AtomicLong scanCount = new AtomicLong(0);
    /** 累计报告的异常数 */
    private final AtomicLong totalAnomalies = new AtomicLong(0);

    private Watchdog() {
    }

    public static synchronized Watchdog getInstance() {
        if (instance == null) {
            instance = new Watchdog();
        }
        return instance;
    }

    /**
     * 注册维度已卸载，用于后续扫描检测孤儿任务。
     * <p>
     * 由 {@link ChunkScheduler#onDimensionUnload} 调用。
     */
    public void registerDimensionUnload(ResourceKey<Level> dimension) {
        unloadedDimensions.add(dimension);
    }

    /**
     * 维度重新加载时清除卸载标记。
     */
    public void clearDimensionUnload(ResourceKey<Level> dimension) {
        unloadedDimensions.remove(dimension);
    }

    /**
     * 每 tick 由调度器调用，按 {@link #scanIntervalTicks} 间隔执行扫描。
     *
     * @param currentTick 当前服务器 tick
     * @param scheduler   调度器实例，用于获取任务列表和诊断数据
     */
    public void tick(long currentTick, ChunkScheduler scheduler) {
        if (currentTick - lastScanTick.get() < scanIntervalTicks) {
            return;
        }
        lastScanTick.set(currentTick);
        scan(scheduler);
    }

    /**
     * 执行一次扫描，检测所有异常项。
     * <p>
     * §17.3 明确要求：只报告，不自动杀线程。
     */
    private void scan(ChunkScheduler scheduler) {
        scanCount.incrementAndGet();
        long now = System.nanoTime();
        int anomalies = 0;
        int stuckQueued = 0;
        int stuckRunning = 0;
        int orphanTasks = 0;

        for (ChunkTask task : scheduler.taskGraph().allTasks()) {
            TaskState state = task.state();
            // 终态任务跳过（不应残留在图中，但防御性检查）
            if (state == TaskState.DONE || state == TaskState.CANCELLED
                    || state == TaskState.FAILED) {
                continue;
            }

            // 检查排队超时（QUEUED / WAITING_DEPS / READY）
            if (state == TaskState.QUEUED || state == TaskState.WAITING_DEPS
                    || state == TaskState.READY) {
                long queueAgeMs = (now - task.queueEnterNanos()) / 1_000_000L;
                if (queueAgeMs > queueTimeoutMs) {
                    stuckQueued++;
                    SteadyChunks.LOGGER.warn(
                            "SteadyChunks Watchdog: 任务排队过久 taskId={} pos={} state={} ageMs={}",
                            task.taskId(), task.pos(), state, queueAgeMs);
                }
            }

            // 检查 RUNNING 超时
            if (state == TaskState.RUNNING) {
                long stageStart = task.stageStartNanos();
                if (stageStart > 0) {
                    long runMs = (now - stageStart) / 1_000_000L;
                    if (runMs > runningTimeoutMs) {
                        stuckRunning++;
                        SteadyChunks.LOGGER.warn(
                                "SteadyChunks Watchdog: 任务执行过久 taskId={} pos={} target={} runMs={}",
                                task.taskId(), task.pos(), task.targetStatus(), runMs);
                    }
                }
            }

            // 检查维度孤儿任务（§17.3 维度已卸载但任务仍存在）
            if (unloadedDimensions.contains(task.dimension())) {
                orphanTasks++;
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks Watchdog: 孤儿任务（维度已卸载）taskId={} pos={} dim={} state={}",
                        task.taskId(), task.pos(), task.dimension().location(), state);
            }
        }

        // permit 泄漏检测：inflightCount 应等于 RUNNING + CANCEL_REQUESTED 任务数
        int inflight = scheduler.inflightCount();
        int actualRunning = 0;
        for (ChunkTask task : scheduler.taskGraph().allTasks()) {
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.CANCEL_REQUESTED) {
                actualRunning++;
            }
        }
        if (inflight != actualRunning) {
            SteadyChunks.LOGGER.warn(
                    "SteadyChunks Watchdog: permit 计数不一致 inflight={} actualRunning={}（可能泄漏）",
                    inflight, actualRunning);
            anomalies++;
        }

        anomalies += stuckQueued + stuckRunning + orphanTasks;
        if (anomalies > 0) {
            totalAnomalies.addAndGet(anomalies);
            SteadyChunks.LOGGER.warn(
                    "SteadyChunks Watchdog: 扫描发现异常 total={} stuckQueued={} stuckRunning={} orphan={}",
                    anomalies, stuckQueued, stuckRunning, orphanTasks);
        }
    }

    /**
     * 清空所有诊断状态（服务器关闭或世界卸载时调用）。
     */
    public void clear() {
        unloadedDimensions.clear();
        lastScanTick.set(0);
    }

    // 配置访问器
    public void setScanIntervalTicks(int ticks) { this.scanIntervalTicks = ticks; }
    public void setQueueTimeoutMs(long ms) { this.queueTimeoutMs = ms; }
    public void setRunningTimeoutMs(long ms) { this.runningTimeoutMs = ms; }

    // 诊断访问器
    public long scanCount() { return scanCount.get(); }
    public long totalAnomalies() { return totalAnomalies.get(); }
    public int unloadedDimensionCount() { return unloadedDimensions.size(); }
}
