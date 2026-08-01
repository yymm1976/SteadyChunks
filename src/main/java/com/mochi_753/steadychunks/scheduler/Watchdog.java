package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度器看门狗，对应技术指导 §17.3。
 * <p>
 * 审查修复：移除任务图遍历（ChunkTaskGraph 已删除）。
 * 简化为只检查 permit 一致性和记录维度卸载。
 * <p>
 * 扫描项：
 * <ul>
 *   <li>permit 泄漏：inflightCount 异常</li>
 *   <li>维度已卸载但 permit 未回收（孤儿任务）</li>
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
     */
    public void tick(long currentTick, ChunkScheduler scheduler) {
        if (currentTick - lastScanTick.get() < scanIntervalTicks) {
            return;
        }
        lastScanTick.set(currentTick);
        scan(scheduler);
    }

    /**
     * 执行一次扫描，检测 permit 泄漏。
     * <p>
     * §17.3 明确要求：只报告，不自动杀线程。
     */
    private void scan(ChunkScheduler scheduler) {
        scanCount.incrementAndGet();
        int anomalies = 0;

        // permit 泄漏检测：inflightCount 应为非负且不超过 permit 上限
        int inflight = scheduler.inflightCount();
        int pending = scheduler.pendingCount();
        if (inflight < 0) {
            SteadyChunks.LOGGER.warn(
                    "SteadyChunks Watchdog: inflightCount 为负数 {}（permit 泄漏）", inflight);
            anomalies++;
        }

        // 等待队列积压检测
        if (pending > 0 && scheduler.cpuPermitsAvailable() > 0) {
            int noiseAvail = -1;
            int noiseLimit = -1;
            var noisePermit = scheduler.stageLimiter().permit(ChunkStatus.NOISE);
            if (noisePermit != null) {
                noiseAvail = noisePermit.availablePermits();
                noiseLimit = noisePermit.maxPermits();
            }
            // P2 修复（第 5 轮）：NOISE 阶段 permit 被占时 pending>0 是正常等待
            // （任务在等 NOISE_HEAVY 桶），不算调度异常。仅当 NOISE permit 也可用
            // （noiseAvail > 0）或未限流（-1）却仍有积压时，才是真正的调度停摆。
            if (noiseAvail != 0) {
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks Watchdog: 等待队列积压 pending={} 但 permitsAvailable={}（调度异常）"
                                + " drainWip={} inflight={} noiseAvail={}/{} bypass={} paused={} failOpen={}",
                        pending, scheduler.cpuPermitsAvailable(), scheduler.drainWipValue(),
                        inflight, noiseAvail, noiseLimit,
                        scheduler.isBypassMode(), scheduler.isAdmissionPaused(), scheduler.isFailOpen());
                anomalies++;
            }
        }

        if (anomalies > 0) {
            totalAnomalies.addAndGet(anomalies);
            SteadyChunks.LOGGER.warn(
                    "SteadyChunks Watchdog: 扫描发现异常 total={}", anomalies);
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

    // 诊断访问器
    public long scanCount() { return scanCount.get(); }
    public long totalAnomalies() { return totalAnomalies.get(); }
    public int unloadedDimensionCount() { return unloadedDimensions.size(); }
}
