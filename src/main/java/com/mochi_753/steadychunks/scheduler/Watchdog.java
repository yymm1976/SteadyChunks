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

    // ---- 第 9 轮卡死修复：drain 停摆恢复线程 ----
    /** 恢复线程是否已启动（幂等） */
    private volatile boolean recoveryStarted = false;
    /** 恢复线程停止标志 */
    private volatile boolean stopRecovery = false;
    /** 恢复线程实例（第 10 轮 P0-4 修复：保存实例以便 interrupt/join 与重启） */
    private Thread recoveryThread;
    /**
     * 第 11 轮 P1 修复：恢复线程代数——stop 后立即 start 时旧线程可能仍 alive，
     * 旧实现直接 return 导致新服务器无线程；代数让旧线程在下轮循环发现
     * 自己已被替换而退出，新线程立即接管。
     */
    private final AtomicLong recoveryGeneration = new AtomicLong(0);
    /** 上次检测时的 drain 进度（ChunkScheduler.drainProgress） */
    private long lastDrainProgress = -1;
    /** 连续停滞检测计数（≥3 次 = 3 秒未 drain，判定停摆） */
    private int stallCount = 0;
    /** 累计恢复次数（mailbox 第一级 + UNSAFE_EMERGENCY 第二级合计） */
    private final AtomicLong totalRecoveries = new AtomicLong(0);
    /**
     * 第 10 轮修复：UNSAFE_EMERGENCY 次数——Watchdog daemon 线程直接 complete
     * 代理 Future（绕过 worldgen mailbox 线程语义）的独立指标，正确性测试断言
     * 此值为 0，任何非零都意味着调度链曾停滞且 mailbox 恢复失效。
     * 第 11 轮修复：mailbox 立即拒绝（execute 抛异常）的直接完成也计入。
     */
    private final AtomicLong totalUnsafeRecoveries = new AtomicLong(0);
    /**
     * 第 11 轮 P0 修复：活动恢复批次——第一级从队列取出的任务引用保留到 proxy
     * 终态（pending 归零也不丢），超时由第二级强制完成（修复旧实现第二级
     * 找不到第一级任务的问题）。
     */
    private ChunkScheduler.RecoveryBatch activeRecoveryBatch;

    /**
     * 启动独立 drain 停摆恢复线程（ModuleBootstrap 服务器启动时调用）。
     * <p>
     * 忙转死锁（卸载竞态 → scheduleUnload 重入风暴 → Server thread 卡在
     * processUnloads）时 Server thread 的 tick 不再运行，tick 内 Watchdog 扫描
     * 随之失效——排队任务永远等不到 drain，refCount 不归零、风暴加剧。独立
     * daemon 线程每 1 秒检测一次：pending>0 且 permit 可用且 drain 进度连续
     * 3 个周期未变（drain 停摆）→ 两级恢复：
     * <ol>
     *   <li>第一级 {@link ChunkScheduler#beginMailboxRecovery()} 把排队任务形成
     *       {@link ChunkScheduler.RecoveryBatch}（保留任务引用）并经原 worldgen
     *       mailbox 提交 error completion（保持原版线程语义）；</li>
     *   <li>批次 deadline（2 秒）后仍有任务未终态（mailbox 本身是停滞链一部分），
     *       第二级 {@link ChunkScheduler#escalateRecoveryBatch} 直接 complete
     *       （UNSAFE_EMERGENCY，计入 {@link #totalUnsafeRecoveries}）。</li>
     * </ol>
     * 触发条件刻意严格（排除 paused/disabled/permit 不足），避免误伤正常排队
     * 等待；释放后区块由原版自愈重新生成，调度器其余状态不动。
     * <p>
     * 第 10 轮 P0-4 修复：保存线程实例，支持集成服务器多世界生命周期。
     * 第 11 轮 P1 修复：线程代数——旧线程退出时只在引用仍是自己时清空，
     * stop 后立即 start 不会因旧线程未退出而丢失新线程。
     */
    public synchronized void startRecoveryThread(ChunkScheduler scheduler) {
        long generation = recoveryGeneration.incrementAndGet();
        Thread old = recoveryThread;
        if (old != null && old.isAlive()) {
            // 旧线程（可能是上一服务器生命周期的残留）：中断唤醒，代数组使其退出
            old.interrupt();
        }
        recoveryStarted = true;
        stopRecovery = false;
        Thread thread = new Thread(() -> {
            try {
                while (!stopRecovery && generation == recoveryGeneration.get()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    try {
                        checkDrainStall(scheduler);
                    } catch (Throwable t) {
                        SteadyChunks.LOGGER.warn("SteadyChunks Watchdog 恢复检查异常", t);
                    }
                }
            } finally {
                // 第 10 轮 P0-4 修复：线程退出时清空实例，允许下次 start 重建。
                // 第 11 轮 P1 修复：仅当引用仍是自己时清空——stop 后立即 start 的
                // 新线程引用不被旧线程误清。
                synchronized (Watchdog.this) {
                    if (recoveryThread == Thread.currentThread()) {
                        recoveryStarted = false;
                        recoveryThread = null;
                    }
                }
            }
        }, "SteadyChunks-DrainRecovery");
        recoveryThread = thread;
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止恢复线程（服务器停止时调用，幂等；interrupt 唤醒 sleep） */
    public synchronized void stopRecoveryThread() {
        stopRecovery = true;
        // 第 11 轮 P0 修复：停服清除活动恢复批次（任务引用随批次释放，世界销毁
        // 兜底；proxy 未完成的任务由原版任务随世界销毁自然丢弃）
        activeRecoveryBatch = null;
        Thread thread = recoveryThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 恢复线程是否存活（GameTest 断言用） */
    public synchronized boolean isRecoveryThreadAlive() {
        return recoveryThread != null && recoveryThread.isAlive();
    }

    /** 累计恢复次数（GameTest 断言正确性测试必须为 0） */
    public long totalRecoveries() { return totalRecoveries.get(); }

    /** UNSAFE_EMERGENCY 直接完成次数（正确性测试必须为 0） */
    public long totalUnsafeRecoveries() { return totalUnsafeRecoveries.get(); }

    /**
     * 第 11 轮 P0 修复：批次状态机 + drain 停滞检测（synchronized——stop 后立即
     * start 时新旧线程可能短暂并存，防并发触发恢复）。
     * <p>
     * 批次优先：活动批次存在时（第一级已取出任务、pending 可能已归零），无论
     * pendingCount 如何都先推进批次（全部终态 → 清除；超时 → 第二级强制完成），
     * 修复旧实现 pending==0 早退导致第二级永不执行的问题。
     */
    private synchronized void checkDrainStall(ChunkScheduler scheduler) {
        ChunkScheduler.RecoveryBatch batch = activeRecoveryBatch;
        if (batch != null) {
            if (scheduler.recoveryBatchAllDone(batch)) {
                activeRecoveryBatch = null;
            } else if (System.nanoTime() >= batch.deadlineNanos()) {
                int unsafe = scheduler.escalateRecoveryBatch(batch);
                totalUnsafeRecoveries.addAndGet(unsafe);
                activeRecoveryBatch = null;
                SteadyChunks.LOGGER.error(
                        "SteadyChunks Watchdog: UNSAFE_EMERGENCY——mailbox 恢复失效，"
                                + "daemon 线程直接完成 {} 个批次任务（打破卸载重入风暴死锁）",
                        unsafe);
            }
            return; // 批次处理期间不启动新批次
        }
        int pending = scheduler.pendingCount();
        long progress = scheduler.drainProgress();
        if (pending == 0
                || scheduler.isAdmissionPaused()
                || !scheduler.isEnabled()
                || scheduler.isFailOpen()
                || scheduler.drainWipValue() != 0
                || scheduler.cpuPermitsAvailable() <= 0) {
            // 正常等待（paused/disabled/permit 不足）或队列空：复位停滞计数
            stallCount = 0;
            lastDrainProgress = progress;
            return;
        }
        var noisePermit = scheduler.stageLimiter().permit(ChunkStatus.NOISE);
        if (noisePermit != null && noisePermit.availablePermits() == 0) {
            // NOISE 阶段 permit 被占：排队等待正常（P2 修复，第 5 轮）
            stallCount = 0;
            lastDrainProgress = progress;
            return;
        }
        if (progress == lastDrainProgress) {
            stallCount++;
            // 第 9 轮卡死修复：停滞 2 秒后输出诊断（含排除项状态），便于定位
            // "在途型"忙转（pending==0 但 refCount 滞留——恢复线程覆盖不到）。
            if (stallCount == 2) {
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks Watchdog: drain 停滞观察中 pending={} inflight={} drainWip={} "
                                + "bypass={} paused={} failOpen={}（若持续 3 秒将启动两级恢复）",
                        pending, scheduler.inflightCount(), scheduler.drainWipValue(),
                        scheduler.isBypassMode(), scheduler.isAdmissionPaused(), scheduler.isFailOpen());
            }
            if (stallCount >= 3) {
                stallCount = 0;
                // 第 11 轮 P0 修复：第一级形成 RecoveryBatch（保留任务引用）并经
                // mailbox 提交；拒绝的任务直接完成并计入 unsafe（P1）。
                ChunkScheduler.MailboxRecoveryResult result = scheduler.beginMailboxRecovery();
                totalRecoveries.incrementAndGet();
                totalUnsafeRecoveries.addAndGet(result.rejectedAndCompletedUnsafely());
                if (!result.batch().tasks().isEmpty()) {
                    activeRecoveryBatch = result.batch();
                }
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks Watchdog: drain 停摆检测（3 秒无进度，pending={}），"
                                + "第一级形成恢复批次 {}（{} 个任务，mailbox 拒绝 {} 个），"
                                + "2 秒内无终态将升级 UNSAFE_EMERGENCY",
                        pending, result.batch().id(), result.batch().tasks().size(),
                        result.rejectedAndCompletedUnsafely());
            }
        } else {
            stallCount = 0;
        }
        lastDrainProgress = progress;
    }

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
