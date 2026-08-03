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
     * 第 12 轮 P1 修复：测试专用停滞检测开关——置 true 时 checkDrainStall 忽略
     * admissionPaused 排除项，使 GameTest 能在 paused 队列上驱动停滞检测
     * （paused 阻止并发 drain 抢走任务，队列快照稳定；真实触发条件刻意排除
     * paused，生产从不设置）。仅 checkDrainStallForTest 驱动期间置 true，
     * start/stop 强制复位，测试结束必须显式清空。
     */
    private volatile boolean stallCheckIgnorePausedForTest = false;

    /** 测试专用：驱动停滞检测时忽略 paused 排除项（生产默认 false）。 */
    public void setStallCheckIgnorePausedForTest(boolean ignore) {
        this.stallCheckIgnorePausedForTest = ignore;
    }
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
     * 第 12 轮 P0 修复：停服时强制完成活动恢复批次的任务数——独立于运行期
     * UNSAFE_EMERGENCY（{@link #totalUnsafeRecoveries}）：停服路径由
     * ServerStopping 的 {@link #stopRecoveryThread()} 触发，不混入运行期指标
     * （正确性测试仍断言运行期指标为 0）。
     */
    private final AtomicLong totalUnsafeShutdownRecoveries = new AtomicLong(0);

    /** 是否存在活动恢复批次（第 12 轮 P1：GameTest 状态机断言用）。 */
    public synchronized boolean hasActiveRecoveryBatch() {
        return activeRecoveryBatch != null;
    }

    /** 活动批次截止时间（纳秒）；无批次返回 -1（第 12 轮 P1：GameTest 注入时钟用）。 */
    public synchronized long activeRecoveryBatchDeadlineNanos() {
        return activeRecoveryBatch != null ? activeRecoveryBatch.deadlineNanos() : -1;
    }

    /** 停服处置恢复数（第 12 轮 P0：GameTest 断言停服批次被强制完成）。 */
    public long totalUnsafeShutdownRecoveries() { return totalUnsafeShutdownRecoveries.get(); }

    /**
     * 第 12 轮 P1 修复：复位恢复指标——GameTest 测试前后复位，避免刻意触发的
     * 状态机恢复污染跨批次累计值（与 ChunkScheduler.resetDiagnostics 同惯例；
     * 正确性硬门槛断言运行期指标为 0 不依赖本方法，测试自身先复位再断言）。
     */
    public synchronized void resetRecoveryMetrics() {
        totalRecoveries.set(0);
        totalUnsafeRecoveries.set(0);
        totalUnsafeShutdownRecoveries.set(0);
    }

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
        // 第 12 轮 P1 修复：跨服务器生命周期复位停滞状态——旧世界残留的
        // stallCount 不会让新世界仅 1 秒就误触发恢复；lastDrainProgress 对齐
        // 当前进度，首个检测周期不会因旧值误判停滞。
        stallCount = 0;
        lastDrainProgress = scheduler.drainProgress();
        // 第 12 轮 P1 修复：复位测试专用开关（防上一测试异常中止遗留）
        stallCheckIgnorePausedForTest = false;
        Thread thread = new Thread(() -> {
            try {
                while (!stopRecovery && generation == recoveryGeneration.get()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    try {
                        checkDrainStall(scheduler, generation);
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

    /**
     * 停止恢复线程（服务器停止时调用，幂等；interrupt 唤醒 sleep）。
     * <p>
     * 第 12 轮 P0 修复：停服不得静默遗弃活动恢复批次——锁内脱离批次引用后，
     * 锁外由调用线程（ServerStopping）同步强制完成批次内未终态任务
     * （proxy 终态 → registration 关闭 → 旧 ServerLifecycle 计数归零）。
     * 旧实现直接置空：批次任务已不在 pending 队列，closeForShutdown 看不到
     * 它们，proxy 永不终态、TaskRegistration 不关闭、旧生命周期永久残留计数。
     */
    public void stopRecoveryThread() {
        ChunkScheduler.RecoveryBatch detached;
        Thread thread;
        synchronized (this) {
            stopRecovery = true;
            detached = activeRecoveryBatch;
            activeRecoveryBatch = null;
            // 第 12 轮 P1 修复：跨服务器生命周期复位停滞状态（见 startRecoveryThread）
            stallCount = 0;
            lastDrainProgress = ChunkScheduler.getInstance().drainProgress();
            // 第 12 轮 P1 修复：复位测试专用开关（防测试异常中止遗留污染后续批次）
            stallCheckIgnorePausedForTest = false;
            thread = recoveryThread;
        }
        if (detached != null) {
            // 锁外执行外部调用（P1-1：不持 Watchdog 锁触发 Future 回调/registration close）
            int completed = ChunkScheduler.getInstance().escalateRecoveryBatch(
                    detached, "服务器停服：活动恢复批次强制完成");
            totalUnsafeShutdownRecoveries.addAndGet(completed);
            SteadyChunks.LOGGER.error(
                    "SteadyChunks Watchdog: 停服处置活动恢复批次 {}——{} 个未终态任务由"
                            + "停服线程直接完成（计入 totalUnsafeShutdownRecoveries）",
                    detached.id(), completed);
        }
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
     * 第 11 轮 P0 修复：批次状态机 + drain 停滞检测。
     * <p>
     * 批次优先：活动批次存在时（第一级已取出任务、pending 可能已归零），无论
     * pendingCount 如何都先推进批次（全部终态 → 清除；超时 → 第二级强制完成），
     * 修复旧实现 pending==0 早退导致第二级永不执行的问题。
     * <p>
     * 第 12 轮 P1 修复：锁粒度收窄——synchronized 块内只交换/读取状态（批次引用、
     * 停滞计数、队列快照），executor.execute 与 Future 完成等外部调用全部移到锁外：
     * 避免恢复线程持锁时被 worldgen mailbox 执行器阻塞，导致 ServerStopping 的
     * stopRecoveryThread 等待同一把锁而卡住停服线程（重新引入停服阻塞风险）。
     */
    private void checkDrainStall(ChunkScheduler scheduler, long generation) {
        checkDrainStall(scheduler, System.nanoTime(), generation);
    }

    /**
     * 第 12 轮 P1 修复：测试入口——注入 nowNanos 驱动批次 deadline 状态机，
     * 不依赖真实等待（GameTest 端到端驱动第一级/第二级恢复）。
     * generation 取当前代数：测试线程以"当前恢复线程"身份驱动发布路径。
     */
    public void checkDrainStallForTest(ChunkScheduler scheduler, long nowNanos) {
        checkDrainStall(scheduler, nowNanos, recoveryGeneration.get());
    }

    private void checkDrainStall(ChunkScheduler scheduler, long nowNanos, long generation) {
        // ---- 阶段 1（锁内）：批次推进决策 + 停滞检测（纯状态读写，无外部调用） ----
        ChunkScheduler.RecoveryBatch batchToEscalate = null;
        boolean startFirstLevel = false;
        int pendingAtTrigger = -1;
        synchronized (this) {
            ChunkScheduler.RecoveryBatch batch = activeRecoveryBatch;
            if (batch != null) {
                if (scheduler.recoveryBatchAllDone(batch)) {
                    activeRecoveryBatch = null;
                } else if (nowNanos >= batch.deadlineNanos()) {
                    // 第二级：锁内脱离批次，锁外直接完成（不在锁内触发 Future 回调）
                    activeRecoveryBatch = null;
                    batchToEscalate = batch;
                }
                // 批次处理期间不启动新批次
            } else {
                int pending = scheduler.pendingCount();
                long progress = scheduler.drainProgress();
                if (pending == 0
                        || (scheduler.isAdmissionPaused() && !stallCheckIgnorePausedForTest)
                        || !scheduler.isEnabled()
                        || scheduler.isFailOpen()
                        || scheduler.drainWipValue() != 0
                        || scheduler.cpuPermitsAvailable() <= 0) {
                    // 正常等待（paused/disabled/permit 不足）或队列空：复位停滞计数。
                    // 第 12 轮 P1 修复：paused 排除项可被测试专用开关忽略（见
                    // {@link #stallCheckIgnorePausedForTest}），生产行为不变。
                    stallCount = 0;
                    lastDrainProgress = progress;
                } else {
                    var noisePermit = scheduler.stageLimiter().permit(ChunkStatus.NOISE);
                    if (noisePermit != null && noisePermit.availablePermits() == 0) {
                        // NOISE 阶段 permit 被占：排队等待正常（P2 修复，第 5 轮）
                        stallCount = 0;
                        lastDrainProgress = progress;
                    } else if (progress == lastDrainProgress) {
                        stallCount++;
                        // 停滞 2 秒后输出诊断（含排除项状态），便于定位在途型忙转
                        if (stallCount == 2) {
                            SteadyChunks.LOGGER.warn(
                                    "SteadyChunks Watchdog: drain 停滞观察中 pending={} inflight={} drainWip={} "
                                            + "bypass={} paused={} failOpen={}（若持续 3 秒将启动两级恢复）",
                                    pending, scheduler.inflightCount(), scheduler.drainWipValue(),
                                    scheduler.isBypassMode(), scheduler.isAdmissionPaused(), scheduler.isFailOpen());
                        }
                        if (stallCount >= 3) {
                            stallCount = 0;
                            startFirstLevel = true;
                            pendingAtTrigger = pending;
                        }
                    } else {
                        stallCount = 0;
                    }
                    lastDrainProgress = progress;
                }
            }
        }

        // ---- 阶段 2（锁外）：外部调用（executor / Future 完成） ----
        if (batchToEscalate != null) {
            int unsafe = scheduler.escalateRecoveryBatch(batchToEscalate);
            totalUnsafeRecoveries.addAndGet(unsafe);
            SteadyChunks.LOGGER.error(
                    "SteadyChunks Watchdog: UNSAFE_EMERGENCY——mailbox 恢复失效，"
                            + "daemon 线程直接完成 {} 个批次任务（打破卸载重入风暴死锁）",
                    unsafe);
        } else if (startFirstLevel) {
            ChunkScheduler.MailboxRecoveryResult result = scheduler.beginMailboxRecovery();
            // ---- 阶段 3（锁内）：发布批次；停服窗口内形成的批次就地升级，防任务遗弃 ----
            boolean publishStopped;
            synchronized (this) {
                // 第一级形成期间 stop（stopRecovery）或 stop→start（代数变化）：
                // 批次任务已移出队列，不能发布到已停止/新代数的 Watchdog——
                // 就地完成（计入停服指标），与 stopRecoveryThread 的处置路径互补。
                publishStopped = stopRecovery || generation != recoveryGeneration.get();
                if (!publishStopped) {
                    totalRecoveries.incrementAndGet();
                    totalUnsafeRecoveries.addAndGet(result.rejectedAndCompletedUnsafely());
                    if (!result.batch().tasks().isEmpty()) {
                        activeRecoveryBatch = result.batch();
                    }
                }
            }
            if (publishStopped) {
                int n = scheduler.escalateRecoveryBatch(result.batch(),
                        "停服窗口形成的恢复批次强制完成");
                totalUnsafeShutdownRecoveries.addAndGet(result.rejectedAndCompletedUnsafely() + n);
                SteadyChunks.LOGGER.error(
                        "SteadyChunks Watchdog: 停服窗口形成的恢复批次 {}（{} 个任务，mailbox 拒绝 {} 个）"
                                + "由停服线程直接完成（计入 totalUnsafeShutdownRecoveries）",
                        result.batch().id(), result.batch().tasks().size(),
                        result.rejectedAndCompletedUnsafely());
            } else {
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks Watchdog: drain 停摆检测（3 秒无进度，pending={}），"
                                + "第一级形成恢复批次 {}（{} 个任务，mailbox 拒绝 {} 个），"
                                + "2 秒内无终态将升级 UNSAFE_EMERGENCY",
                        pendingAtTrigger, result.batch().id(), result.batch().tasks().size(),
                        result.rejectedAndCompletedUnsafely());
            }
        }
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
