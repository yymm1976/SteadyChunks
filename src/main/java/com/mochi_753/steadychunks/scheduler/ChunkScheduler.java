package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.mixin.server.ChunkMapAccessor;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 区块调度器主控，对应开发计划 §3 交付物。
 * <p>
 * 审查修复：从"任务图管理器"简化为"NOISE 准入控制器"。
 * <ul>
 *   <li>删除 ChunkTaskGraph（原版 ChunkGenerationTask 已处理依赖）</li>
 *   <li>新增 controlAdmission() 供 Mixin 拦截 applyStep 后调用</li>
 *   <li>permit 不足时创建代理 Future，任务进入等待队列</li>
 *   <li>tick() 处理等待队列，permit 可用时唤醒</li>
 *   <li>禁用调度器后行为恢复原版路径（验收标准 §3）</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>仅门控 NOISE（PR1 范围），其他阶段透传原版</li>
 *   <li>不自建工作线程池，permit 可用时向原版同一 Executor 提交</li>
 *   <li>不重写依赖，原版 ChunkGenerationTask 负责邻区块依赖解析</li>
 * </ul>
 */
public final class ChunkScheduler {
    private static ChunkScheduler instance;

    private final StageLimiter stageLimiter = new StageLimiter();
    /** §17.3 看门狗，定期扫描任务异常并报告 */
    private final Watchdog watchdog = Watchdog.getInstance();
    /** 软取消策略（审查修复：无参构造，不再依赖 ChunkTaskGraph） */
    private final CancellationPolicy cancellation = new CancellationPolicy();

    /** NOISE 等待队列：permit 不足时暂存任务 */
    private final ConcurrentLinkedQueue<PendingNoiseTask> pendingNoiseTasks = new ConcurrentLinkedQueue<>();

    /** P0-1 修复（第 5 轮）：单一 drainer WIP 计数（0=空闲，>0=drain 进行中），序列化所有 peek/poll */
    private final AtomicInteger drainWip = new AtomicInteger(0);
    /** 诊断：drain WIP 当前值（Watchdog 用，判断 drain 是否泄漏） */
    public int drainWipValue() { return drainWip.get(); }
    /** P1-3 修复：禁用调度器时的有节奏放行模式（不再一次性同步启动全部积压任务） */
    private final AtomicBoolean bypassMode = new AtomicBoolean(false);
    /** P1-3：每 tick 放行的最大 bypass 任务数 */
    private static final int BYPASS_BATCH_PER_TICK = 16;
    /**
     * P1 修复（第 4 轮）：bypass 每 tick 放行预算。
     * 仅由服务器 Tick 补充（{@link #tick()}），Future 完成回调虽能触发 requestDrain，
     * 但不能补充预算，避免"关闭调度器瞬间单 tick 放行全部积压"的洪峰。
     */
    private final AtomicInteger bypassBudget = new AtomicInteger(0);

    /**
     * P1 修复（第 4 轮）：等待队列告警阈值。
     * 超过时仅记录告警（高水位指标监控），<b>不是</b>硬上限——真正背压需前移到原版
     * 任务被取出之前（见 {@link #pendingCriticalThreshold} 的 fail-open 软保护）。
     */
    private volatile int pendingWarningThreshold = 512;
    /** P1 修复（第 4 轮）：等待队列紧急阈值，超过则临时 fail-open 透传，避免代理 Future 无限积压 */
    private volatile int pendingCriticalThreshold = 1024;
    /** P1 修复（第 4 轮）：fail-open 标志。置位期间 controlAdmission 直接透传原版操作。 */
    private final AtomicBoolean failOpen = new AtomicBoolean(false);
    /** P1 修复（第 5 轮）：fail-open 持续状态摘要日志的最小间隔（毫秒） */
    private static final long FAIL_OPEN_LOG_INTERVAL_MILLIS = 10_000L;
    /** P1-2：等待队列历史峰值（高水位指标） */
    private final AtomicInteger pendingHighWatermark = new AtomicInteger(0);

    /**
     * P0 修复（第 4 轮）：生命周期接收标志。clearAll 期间置 false（拒绝新入队），
     * 清理完成后恢复 true。用于修复"clearAll 与并发 enqueuePending"的残留竞态。
     */
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);
    /**
     * P0 修复（第 4 轮）：生命周期代数。每次 clearAll 递增，入队任务记录入队时的代数，
     * 入队后二次校验代数，捕获"清理期间并发入队"的任务并异常完成。
     */
    private final AtomicLong lifecycleGeneration = new AtomicLong(0);

    /** P1 修复（第 4 轮）：Mixin 拦截计数（controlAdmission 接管 NOISE 的次数，真实生成测试断言用） */
    private final AtomicLong mixinInterceptCount = new AtomicLong(0);
    /** P1 修复（第 4 轮）：NOISE 在途任务峰值（真实生成测试验证并发上限） */
    private final AtomicInteger maxActiveNoise = new AtomicInteger(0);
    /** P1 修复（第 5 轮）：fail-open 透传中的非受控 NOISE 任务数（不占 permit，仍统计） */
    private final AtomicInteger uncontrolledNoiseActive = new AtomicInteger(0);
    /** P1 修复（第 5 轮）：NOISE 总活动峰值（受控 + 非受控，反映真实并发） */
    private final AtomicInteger maxTotalNoiseActive = new AtomicInteger(0);
    /** P1 修复（第 5 轮）：fail-open 持续状态下的日志节流时间戳（每 10 秒最多一条摘要） */
    private volatile long lastFailOpenLogMillis = 0L;

    /**
     * P0-1 修复（第 5 轮）：恢复执行器测试注入点。
     * 非 null 时替代 {@link PendingNoiseTask#resumeExecutor()}，用于 GameTest 模拟
     * mailbox 提交失败。生产环境保持 null（走原版 worldgen mailbox）。
     */
    private volatile Executor resumeExecutorOverride = null;

    private final ResourcePermit cpuGeneralPermit;
    private final AtomicInteger inflightCount = new AtomicInteger(0);
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    /** P0-4 修复：紧急暂停标志，独立于 permit 额度。暂停时拒绝普通任务，依赖关键任务旁路。 */
    private volatile boolean admissionPaused = false;
    private volatile double maxVisibleDistance = 128.0;

    /** §17.3 内部 tick 计数器，供 Watchdog 扫描间隔判断 */
    private long tickCounter = 0L;

    private ChunkScheduler() {
        int maxInflight = 64;
        cpuGeneralPermit = new ResourcePermit(ResourceType.CPU_GENERAL, maxInflight);
    }

    public static synchronized ChunkScheduler getInstance() {
        if (instance == null) {
            instance = new ChunkScheduler();
        }
        return instance;
    }

    /**
     * 启用或关闭调度器。关闭后行为恢复原版路径。
     * <p>
     * P1-3 修复：关闭时不再一次性同步执行全部积压任务（会造成 CPU 与内存洪峰），
     * 而是进入 bypassMode，由 tick() 每 tick 有节奏地放行一批任务到原 worldgen 上下文。
     */
    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks 调度器: {}", on ? "enabled" : "disabled");
        if (on) {
            // P1 修复（第 4 轮）：重新启用时取消 bypass，避免旧任务继续绕过 permit 洪峰放行。
            bypassMode.set(false);
            bypassBudget.set(0);
            requestDrain();
        } else {
            bypassMode.set(true);
            requestDrain();
        }
    }

    /**
     * 设置在途任务上限（§11.6 预设应用器调用）。
     */
    public void setMaxInflight(int maxInflight) {
        cpuGeneralPermit.setMaxPermits(maxInflight);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * P0-4 修复：设置紧急暂停。暂停时普通任务进入等待队列，依赖关键任务旁路放行。
     * <p>
     * 旧实现通过 setMaxPermits(0) 暂停，但 ResourceBucket 钳制为 1，实际仍允许 1 个任务。
     * 新实现使用独立标志，真正阻止普通任务准入。
     *
     * @param paused true 表示暂停普通任务准入
     */
    public void setAdmissionPaused(boolean paused) {
        if (this.admissionPaused != paused) {
            this.admissionPaused = paused;
            SteadyChunks.LOGGER.info("SteadyChunks 调度器准入: {}", paused ? "paused" : "resumed");
            // 恢复时唤醒等待队列（P0-1：统一走 requestDrain 单一 drainer）
            if (!paused) {
                requestDrain();
            }
        }
    }

    public boolean isAdmissionPaused() {
        return admissionPaused;
    }

    /**
     * 准入控制：Mixin 在 {@code ChunkGenerationTask.scheduleChunkInLayer} 的
     * {@code GenerationChunkHolder.applyStep} 调用点（@WrapOperation）调用此方法。
     * <p>
     * 流程（审查建议的最小接入路径）：
     * <ol>
     *   <li>调度器未启用 → 直接走原版路径</li>
     *   <li>非 NOISE 阶段 → 直接走原版路径（PR1 仅门控 NOISE）</li>
     *   <li>获取全局 permit（max_inflight）→ 失败入队等待</li>
     *   <li>获取阶段 permit（NOISE_HEAVY）→ 失败释放全局并入队等待</li>
     *   <li>都成功 → 执行原版操作，完成后释放两个 permit</li>
     * </ol>
     * <p>
     * P0 修复：获取顺序固定为 global → stage，释放顺序相反，避免死锁。
     * 组合 lease 在正常、异常、取消路径统一关闭，保证 permit 不泄漏。
     *
     * @param targetStatus       目标 ChunkStatus
     * @param isDependencyUnlock 是否为依赖解锁任务（PR1 阶段恒为 false，预留旁路）
     * @param map                原版 GeneratingChunkMap（P0-2：恢复时提交回 worldgen mailbox）
     * @param holder             原版 GenerationChunkHolder（P0-2：mailbox 消息按区块优先级调度）
     * @param originalOperation  原版 applyStep 操作（返回原版 Future）
     * @return 代理 Future 或原版 Future
     */
    public CompletableFuture<ChunkResult<ChunkAccess>> controlAdmission(
            ChunkStatus targetStatus,
            boolean isDependencyUnlock,
            GeneratingChunkMap map,
            GenerationChunkHolder holder,
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation) {

        // 调度器未启用：直接走原版路径（验收标准 §3）
        if (!enabled.get()) {
            return originalOperation.get();
        }

        // PR1：仅门控 NOISE，其他阶段透传
        if (targetStatus != ChunkStatus.NOISE) {
            return originalOperation.get();
        }

        // P1 修复（第 5 轮）：生命周期关闭时不接受新任务。排队路径（enqueuePending）
        // 已有检查，此处覆盖"直接获 permit 立即执行"的路径，保证 closeForShutdown
        // 后不再启动新任务。
        // P2 修复（第 5 轮）：以 error result <b>正常完成</b> 而非异常完成。原版
        // GenerationChunkHolder.lambda$applyStep$0 的 handle 会把异常完成视为致命
        // （MinecraftServer.setFatalException），打断真实区块生成链导致区块卡死
        // （processUnloads 忙转）；正常 error result 走 completeFuture 路径，区块可恢复。
        if (!acceptingTasks.get()) {
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 调度器已关闭"));
        }

        // P1 修复（第 4 轮）：Mixin 真实拦截计数（供真实生成 GameTest 断言）。
        mixinInterceptCount.incrementAndGet();

        // P1 修复（第 4/5 轮）：队列紧急软保护（fail-open）。
        // 第 5 轮：日志只在状态转换时输出（进入 WARN 一次 / 退出 INFO 一次），
        // 持续状态每 10 秒最多一条摘要；fail-open 透传任务经 runUncontrolled 统计，
        // 避免诊断指标低估真实并发。
        if (failOpen.get()) {
            if (pendingCount.get() <= pendingWarningThreshold) {
                failOpen.set(false);
                SteadyChunks.LOGGER.info("NOISE 等待队列回落至告警阈值以下，恢复准入（pending={}）",
                        pendingCount.get());
            } else {
                logFailOpenThrottled();
                return runUncontrolled(originalOperation);
            }
        }
        if (pendingCount.get() >= pendingCriticalThreshold) {
            failOpen.set(true);
            SteadyChunks.LOGGER.warn("NOISE 等待队列超过紧急阈值 {}，进入 fail-open 透传（pending={}）",
                    pendingCriticalThreshold, pendingCount.get());
            return runUncontrolled(originalOperation);
        }

        // P0-4 修复：紧急暂停时普通任务进入等待队列，依赖关键任务旁路放行
        if (admissionPaused && !isDependencyUnlock) {
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder);
        }

        // 组合 lease：固定获取顺序 global → stage
        PermitLease global = cpuGeneralPermit.tryAcquireLease();
        if (!global.acquired()) {
            // 全局 permit 不足：入队等待
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder);
        }
        PermitLease stage = stageLimiter.tryAcquireLease(targetStatus, isDependencyUnlock);
        if (!stage.acquired()) {
            // 阶段 permit 不足：释放全局 permit 后入队等待
            global.close();
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder);
        }

        // 组合 permit 获取成功：执行原版操作，完成后统一释放
        return executeOriginal(targetStatus, originalOperation, null, global, stage);
    }

    /**
     * P1 修复（第 5 轮）：fail-open 透传执行原版操作，但统计非受控活动。
     * <p>
     * fail-open 不获取 permit（原版路径），但真实并发必须可观测：
     * {@code uncontrolledNoiseActive} 为当前透传数，{@code maxTotalNoiseActive}
     * 为受控 + 非受控总峰值，避免诊断指标低估。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> runUncontrolled(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation) {
        int active = uncontrolledNoiseActive.incrementAndGet();
        maxTotalNoiseActive.accumulateAndGet(active, Math::max);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = originalOperation.get();
        } catch (Throwable ex) {
            uncontrolledNoiseActive.decrementAndGet();
            CompletableFuture<ChunkResult<ChunkAccess>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
        future.whenComplete((result, ex) -> uncontrolledNoiseActive.decrementAndGet());
        return future;
    }

    /**
     * P1 修复（第 5 轮）：fail-open 持续状态的摘要日志节流（每 10 秒最多一条）。
     * 状态转换日志（进入/退出 fail-open）在 controlAdmission 中单独输出，避免洪峰。
     */
    private void logFailOpenThrottled() {
        long now = System.currentTimeMillis();
        long last = lastFailOpenLogMillis;
        if (now - last >= FAIL_OPEN_LOG_INTERVAL_MILLIS && lastFailOpenLogMillis == last) {
            lastFailOpenLogMillis = now;
            SteadyChunks.LOGGER.warn("NOISE 等待队列持续超阈值：fail-open 透传中（pending={}）", pendingCount.get());
        }
    }

    /**
     * 创建代理 Future 并将任务放入等待队列（P1-2：高水位指标 + 告警；P0 修复：生命周期屏障）。
     * <p>
     * P0 修复（第 4 轮）：clearAll 与并发入队的残留竞态。
     * 入队前检查 {@link #acceptingTasks}（清理期间拒绝新任务），入队后二次校验
     * {@link #lifecycleGeneration}（清理已发生则移除任务并异常完成），保证任何
     * 时序下都不会残留无人处理的代理 Future。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> enqueuePending(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            boolean isDependencyUnlock,
            GeneratingChunkMap map,
            GenerationChunkHolder holder) {
        // P0 修复（第 4 轮）：生命周期停止接收时拒绝入队（停服/卸载场景，原版同步关闭中，
        // 返回 error result 等价于生成失败但不触发致命异常，不会残留永久等待的代理 Future）。
        if (!acceptingTasks.get()) {
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 调度器已停止接收任务"));
        }
        long generation = lifecycleGeneration.get();
        int depth = pendingCount.incrementAndGet();
        // P1-2：更新高水位（历史峰值，供诊断与报警）
        pendingHighWatermark.accumulateAndGet(depth, Math::max);
        if (depth > pendingWarningThreshold) {
            // 超过告警阈值：仅记录告警（不是硬上限，见 controlAdmission 的 critical fail-open 软保护）。
            SteadyChunks.LOGGER.warn("NOISE 等待队列超告警阈值: depth={} warning={}（请检查 permit 配置或跑图速度）",
                    depth, pendingWarningThreshold);
        }
        CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
        PendingNoiseTask task = new PendingNoiseTask(originalOperation, proxy, isDependencyUnlock, map, holder);
        pendingNoiseTasks.offer(task);
        // P0 修复（第 4 轮）：入队后二次校验生命周期。若清理已在入队与检查之间发生
        // （generation 变化或停止接收），移除任务并以 error result 正常完成（第 5 轮修复：
        // 异常完成会让原版 setFatalException，破坏真实区块生成链），避免残留。
        if (!acceptingTasks.get() || generation != lifecycleGeneration.get()) {
            if (pendingNoiseTasks.remove(task)) {
                pendingCount.updateAndGet(v -> Math.max(0, v - 1));
                proxy.complete(ChunkResult.error("SteadyChunks 调度器生命周期已变化"));
            }
        }
        // P0-1：入队后唤醒单一 drainer
        requestDrain();
        return proxy;
    }

    /**
     * 执行原版操作，完成后释放组合 permit 并唤醒等待队列。
     * <p>
     * 原版操作执行于当前调用线程（applyStep 为纯调度函数：同步调用
     * {@code ChunkStep.apply} 返回 Future，真正的噪声填充由 Future 内部驱动）。
     * 延迟恢复时该线程是触发 drainPending 的线程（多为原版 Future 完成回调线程）。
     *
     * @param proxy  代理 Future（null 表示直接返回原版 Future）
     * @param global 全局 permit lease（必须非 null）
     * @param stage  阶段 permit lease（必须非 null）
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> executeOriginal(
            ChunkStatus targetStatus,
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            CompletableFuture<ChunkResult<ChunkAccess>> proxy,
            PermitLease global,
            PermitLease stage) {

        // P1 修复（第 4 轮）：记录 NOISE 在途任务峰值（真实生成测试验证并发上限）。
        int active = inflightCount.incrementAndGet();
        maxActiveNoise.accumulateAndGet(active, Math::max);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = originalOperation.get();
        } catch (Throwable ex) {
            // 原版操作抛异常：释放组合 permit 并传播异常（审查修复：统一 close 路径）
            stage.close();
            global.close();
            inflightCount.decrementAndGet();
            requestDrain();
            if (proxy != null) {
                proxy.completeExceptionally(ex);
                return proxy;
            }
            CompletableFuture<ChunkResult<ChunkAccess>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }

        future.whenComplete((result, ex) -> {
            // 释放顺序与获取顺序相反：stage → global
            stage.close();
            global.close();
            inflightCount.decrementAndGet();
            if (proxy != null) {
                // 完成代理 Future
                if (ex != null) {
                    proxy.completeExceptionally(ex);
                } else {
                    proxy.complete(result);
                }
            }
            // 尝试唤醒等待队列（P0-1：只发信号，不递归进入 drain）
            requestDrain();
        });

        return proxy != null ? proxy : future;
    }

    /**
     * 每 tick 调用：处理等待队列中的 NOISE 任务。
     * <p>
     * P1-3：禁用调度器（bypassMode）时也在 tick 推进有节奏放行。
     */
    public void tick() {
        if (!enabled.get() && !bypassMode.get()) {
            return;
        }
        tickCounter++;
        watchdog.tick(tickCounter, this);
        // P1 修复（第 4 轮）：仅在服务器 Tick 补充 bypass 预算。
        // Future 完成回调触发 requestDrain 时预算可能为 0，drain 放行量受严格限制。
        if (bypassMode.get()) {
            bypassBudget.set(BYPASS_BATCH_PER_TICK);
        }
        requestDrain();
        // bypass 队列清空后退出 bypass 模式
        if (bypassMode.get() && pendingNoiseTasks.isEmpty() && pendingCount.get() == 0) {
            bypassMode.set(false);
            bypassBudget.set(0);
        }
    }

    /**
     * P0-1 修复：请求一次 drain 处理。单一 drainer 序列化模型。
     * <p>
     * 任何时刻至多一个线程持有 drain 权（drainWip 从 0→1 者）。其他调用方只递增 WIP
     * 计数（表示"有遗漏的工作"），不进入 drain。持有者处理完一轮后检查遗漏计数，
     * 非零则继续，直到所有排队触发都被消费。
     * <p>
     * 此模型同时解决：
     * <ul>
     *   <li>P0-1：peek/poll 并发丢任务（只有持有者在 poll）</li>
     *   <li>P1-1：同步完成 Future 的 whenComplete 递归 drain（回调只发信号，不递归进入）</li>
     * </ul>
     */
    private void requestDrain() {
        if (drainWip.getAndIncrement() != 0) {
            return; // 已有 drainer 在处理，本次触发记入 missed
        }
        int missed = 1;
        do {
            drainOwnedPass();
            missed = drainWip.addAndGet(-missed);
        } while (missed != 0);
    }

    /**
     * drain 持有者执行一轮任务处理（仅 requestDrain 的持有者调用，无并发 poll）。
     * <p>
     * P0-2 修复（第 5 轮）：bypass 是独立状态，<b>优先于</b> admissionPaused 处理。
     * 紧急暂停期间关闭调度器时，积压任务仍按 bypass 有节奏恢复，不会被暂停挡住。
     */
    private void drainOwnedPass() {
        if (bypassMode.get()) {
            drainBypass();
            return;
        }
        // P0-4：紧急暂停时禁止启动任何普通任务
        if (admissionPaused) {
            return;
        }
        drainControlled();
    }

    /**
     * bypass 模式（调度器已禁用）：不获取 permit，消费 tick 补充的预算有节奏放行。
     * <p>
     * P1 修复（第 4 轮）：消费 tick 补充的预算（getAndSet(0) 防止重复消费/并发补充超发）。
     */
    private void drainBypass() {
        int allowed = bypassBudget.getAndSet(0);
        while (allowed-- > 0) {
            PendingNoiseTask task = pendingNoiseTasks.poll();
            if (task == null) {
                return;
            }
            pendingCount.decrementAndGet();
            // 通过原 worldgen mailbox 提交，恢复原版线程语义（不获取 permit）。
            // P0-1 修复（第 5 轮）：提交失败由 submitResumed 统一兜底。
            submitResumed(task, PermitLease.empty(), PermitLease.empty());
        }
    }

    /**
     * 正常模式：获取组合 permit 后，通过 {@code resumeExecutor}（worldgen mailbox）
     * 异步提交原版操作，恢复回原执行上下文（P0-2）。
     */
    private void drainControlled() {
        while (true) {
            PendingNoiseTask task = pendingNoiseTasks.peek();
            if (task == null) {
                return;
            }
            // 组合 lease：固定获取顺序 global → stage
            PermitLease global = cpuGeneralPermit.tryAcquireLease();
            if (!global.acquired()) {
                return; // 全局 permit 不足，等待下次触发
            }
            PermitLease stage = stageLimiter.tryAcquireLease(ChunkStatus.NOISE, task.isDependencyUnlock());
            if (!stage.acquired()) {
                global.close();
                return; // 阶段 permit 不足，等待下次触发
            }
            // P2 修复（第 5 轮）：poll 出的任务绝不丢弃。
            // clearAll 的 stopAcceptingAndClear 与 drain 持有者并发 poll 同一队列时，
            // poll() 返回的任务可能 != peek() 的任务（peek 的任务已被 clearAll 取走）。
            // 旧防御分支把 poll 出的任务直接丢弃：代理永不完成 + pendingCount 残留，
            // 导致 clearConcurrent GameTest 断言失败、Watchdog 误报积压。
            // 正确语义：poll 到谁就处理谁；poll 返回 null（队列被 clearAll 清空）则释放 permit 退出。
            PendingNoiseTask removed = pendingNoiseTasks.poll();
            if (removed == null) {
                stage.close();
                global.close();
                return;
            }
            pendingCount.decrementAndGet();
            // P0-2：恢复通过原 worldgen mailbox 提交，回到原执行上下文，
            // 不直接在当前线程（可能为 Server Thread）调用 originalOperation。
            submitResumed(removed, global, stage);
        }
    }

    /**
     * P0-1 修复（第 5 轮）：向原执行上下文（worldgen mailbox）提交恢复任务。
     * <p>
     * 若提交本身抛异常（服务器关闭中 mailbox 停止接收、或第三方改动）：
     * <ul>
     *   <li>任务已从等待队列移除（pendingCount 已递减），不会重复恢复；</li>
     *   <li>释放已持有的组合 permit（避免泄漏）；</li>
     *   <li>异常完成代理 Future（调用方可见，而非永久未完成）；</li>
     *   <li>requestDrain 唤醒后续任务。</li>
     * </ul>
     * bypass 路径传入空 lease，释放为空操作。
     */
    private void submitResumed(PendingNoiseTask task, PermitLease global, PermitLease stage) {
        try {
            Executor executor = resumeExecutorOverride != null ? resumeExecutorOverride : task.resumeExecutor();
            executor.execute(() ->
                    executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy(), global, stage));
        } catch (Throwable throwable) {
            stage.close();
            global.close();
            task.proxy().completeExceptionally(throwable);
            requestDrain();
        }
    }

    /**
     * 请求取消任务。
     */
    public boolean cancel(ChunkTask task) {
        return cancellation.cancel(task);
    }

    /**
     * 从配置同步调度器参数。
     */
    public void syncFromConfig() {
        setEnabled(CommonConfig.SCHEDULER_ENABLED.get());
        int maxInflight = CommonConfig.MAX_INFLIGHT.get();
        cpuGeneralPermit.setMaxPermits(maxInflight);

        // 同步阶段限制
        stageLimiter.setStageLimit(ChunkStatus.STRUCTURE_STARTS, CommonConfig.LIMIT_STRUCTURE_STARTS.get());
        stageLimiter.setStageLimit(ChunkStatus.NOISE, CommonConfig.LIMIT_NOISE.get());
        stageLimiter.setStageLimit(ChunkStatus.FEATURES, CommonConfig.LIMIT_FEATURES.get());
        stageLimiter.setStageLimit(ChunkStatus.LIGHT, CommonConfig.LIMIT_LIGHT.get());
    }

    // 诊断访问器
    public StageLimiter stageLimiter() { return stageLimiter; }
    public Watchdog watchdog() { return watchdog; }
    public int inflightCount() { return inflightCount.get(); }
    public int pendingCount() { return pendingCount.get(); }
    public int cpuPermitsAvailable() { return cpuGeneralPermit.availablePermits(); }
    public int cpuPermitsMax() { return cpuGeneralPermit.maxPermits(); }
    /** P1-2：等待队列历史峰值（高水位指标，供诊断/报警） */
    public int pendingHighWatermark() { return pendingHighWatermark.get(); }
    /** P1 修复（第 4 轮）：等待队列告警阈值（超过仅告警，非硬上限） */
    public int pendingWarningThreshold() { return pendingWarningThreshold; }
    public void setPendingWarningThreshold(int max) { this.pendingWarningThreshold = Math.max(1, max); }
    /** P1 修复（第 4 轮）：等待队列紧急阈值（超过进入 fail-open 透传） */
    public int pendingCriticalThreshold() { return pendingCriticalThreshold; }
    /** P1 修复（第 4 轮）：Mixin 真实拦截计数（真实生成 GameTest 断言） */
    public long mixinInterceptCount() { return mixinInterceptCount.get(); }
    /** P1 修复（第 4 轮）：NOISE 在途任务峰值（真实生成 GameTest 断言并发上限） */
    public int maxActiveNoise() { return maxActiveNoise.get(); }
    /** P1 修复（第 5 轮）：fail-open 透传中的非受控 NOISE 任务数（当前值） */
    public int uncontrolledNoiseActive() { return uncontrolledNoiseActive.get(); }
    /** P1 修复（第 5 轮）：NOISE 总活动峰值（受控 + 非受控，反映真实并发） */
    public int maxTotalNoiseActive() { return maxTotalNoiseActive.get(); }
    /** P1 修复（第 4 轮）：是否处于 fail-open 透传（诊断） */
    public boolean isFailOpen() { return failOpen.get(); }
    /** P0-1 修复（第 5 轮）：测试注入恢复执行器（null 表示使用原版 worldgen mailbox） */
    public void setResumeExecutorOverride(Executor executor) { this.resumeExecutorOverride = executor; }
    /** P1 修复（第 4 轮）：重置测试期诊断计数（GameTest 隔离用） */
    public void resetDiagnostics() {
        mixinInterceptCount.set(0);
        maxActiveNoise.set(0);
        maxTotalNoiseActive.set(0);
        uncontrolledNoiseActive.set(0);
        pendingHighWatermark.set(0);
    }
    /** P1-3：是否处于有节奏放行模式（调度器已禁用且队列未清空） */
    public boolean isBypassMode() { return bypassMode.get(); }

    /**
     * §9.4 服务器关闭或维度卸载时调用：异常完成所有等待任务并清空队列。
     * <p>
     * P0 修复（第 4 轮）：clearAll 与并发入队的生命周期竞态。
     * 先停止接收（{@link #acceptingTasks}）并递增代数（{@link #lifecycleGeneration}），
     * 再清空队列。清理期间并发入队的任务会被 enqueuePending 的二次校验捕获并异常完成，
     * 任何时序下都不会残留无人处理的代理 Future。
     * <p>
     * P1 修复（第 5 轮）：拆分为 {@link #resetForReload}（清理后恢复接收，GameTest
     * 重置/运行期 reload）与 {@link #closeForShutdown}（清理后不再接收，服务器永久关闭）。
     * 本方法等价于 {@code resetForReload}，保留旧调用语义。
     * <p>
     * 审查 P1 修复：
     * <ul>
     *   <li>等待队列中的任务已向原版返回代理 Future，直接丢弃会让依赖它们的区块永久等待。
     *       此处对每个代理 Future 调用 {@code completeExceptionally(cause)}。</li>
     *   <li>不重置 inflightCount —— 已在运行的任务仍会自然经过统一完成路径递减，
     *       直接归零会导致计数被减成负数。</li>
     * </ul>
     *
     * @param cause 清理原因（如服务器停止、维度卸载）
     */
    public void clearAll(Throwable cause) {
        resetForReload(cause);
    }

    /**
     * P1 修复（第 5 轮）：清空等待队列并<b>恢复接收</b>（运行期 reload / GameTest 重置）。
     */
    public void resetForReload(Throwable cause) {
        stopAcceptingAndClear(cause);
        acceptingTasks.set(true);
        SteadyChunks.LOGGER.info("SteadyChunks 调度器已重置（等待任务异常完成: {}）", cause.getClass().getSimpleName());
    }

    /**
     * P1 修复（第 5 轮）：清空等待队列并<b>永久停止接收</b>（服务器关闭）。
     * 之后 controlAdmission 的 NOISE 分支直接返回失败 Future，不再启动新任务。
     */
    public void closeForShutdown(Throwable cause) {
        stopAcceptingAndClear(cause);
        SteadyChunks.LOGGER.info("SteadyChunks 调度器已关闭（等待任务异常完成: {}）", cause.getClass().getSimpleName());
    }

    /**
     * P0/P1 修复（第 4/5 轮）：生命周期屏障，先停止接收并递增代数，再清空队列。
     */
    private void stopAcceptingAndClear(Throwable cause) {
        acceptingTasks.set(false);
        lifecycleGeneration.incrementAndGet();
        PendingNoiseTask task;
        while ((task = pendingNoiseTasks.poll()) != null) {
            pendingCount.updateAndGet(v -> Math.max(0, v - 1));
            // P2 修复（第 5 轮）：以 error result 正常完成（而非异常完成）。
            // 原版 GenerationChunkHolder.lambda$applyStep$0 的 handle 会把异常完成视为
            // 致命错误（MinecraftServer.setFatalException），导致真实区块生成链中断、
            // 区块卡在生成中无法卸载（processUnloads 忙转）。error result 走 completeFuture
            // 正常路径，区块状态可恢复，不会卡死。
            task.proxy().complete(ChunkResult.error("SteadyChunks 调度器清理: " + cause));
        }
        watchdog.clear();
    }

    /**
     * 等待中的 NOISE 任务。
     * <p>
     * P0-2 修复：保存原版 {@code GeneratingChunkMap} 与 {@code GenerationChunkHolder}，
     * {@link #resumeExecutor()} 通过原 worldgen mailbox（{@link ChunkMapAccessor}）提交恢复
     * 操作，严格保留原执行器、原线程模型与原调用链时序。
     * 恢复动作提交到 mailbox 后由其 worker 线程执行，不再从 Server Thread 直接调用。
     */
    private record PendingNoiseTask(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> operation,
            CompletableFuture<ChunkResult<ChunkAccess>> proxy,
            boolean isDependencyUnlock,
            GeneratingChunkMap map,
            GenerationChunkHolder holder
    ) {
        /**
         * P0-2 修复：恢复执行器 = 原版 worldgen mailbox。
         * <p>
         * 与原版 {@code ChunkMap.runGenerationTask} 使用完全相同的提交方式：
         * {@code mailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable))}。
         * 任务按区块优先级调度，由 worldgen worker 线程执行。
         */
        Executor resumeExecutor() {
            ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> mailbox =
                    ((ChunkMapAccessor) map).steady$worldgenMailbox();
            return runnable -> mailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable));
        }
    }
}
