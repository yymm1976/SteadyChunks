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

    /** P0-1 修复：单一 drainer WIP 计数（0=空闲，>0=drain 进行中），序列化所有 peek/poll */
    private final AtomicInteger drainWip = new AtomicInteger(0);
    /** P1-3 修复：禁用调度器时的有节奏放行模式（不再一次性同步启动全部积压任务） */
    private final AtomicBoolean bypassMode = new AtomicBoolean(false);
    /** P1-3：每 tick 放行的最大 bypass 任务数 */
    private static final int BYPASS_BATCH_PER_TICK = 16;

    /** P1-2：等待队列上限（防内存膨胀；超过时记录告警，由高水位指标监控） */
    private volatile int maxPendingNoiseTasks = 512;
    /** P1-2：等待队列历史峰值（高水位指标） */
    private final AtomicInteger pendingHighWatermark = new AtomicInteger(0);

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
        if (!on) {
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
     * 创建代理 Future 并将任务放入等待队列（P1-2：高水位指标 + 超限告警）。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> enqueuePending(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            boolean isDependencyUnlock,
            GeneratingChunkMap map,
            GenerationChunkHolder holder) {
        int depth = pendingCount.incrementAndGet();
        // P1-2：更新高水位（历史峰值，供诊断与报警）
        pendingHighWatermark.accumulateAndGet(depth, Math::max);
        if (depth > maxPendingNoiseTasks) {
            // 队列超限：不能异常完成（原版会视为生成失败）。
            // 第一版记录告警，由 pendingHighWatermark 指标持续监控。
            SteadyChunks.LOGGER.warn("NOISE 等待队列超限: depth={} max={}（请检查 permit 配置或跑图速度）",
                    depth, maxPendingNoiseTasks);
        }
        CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
        pendingNoiseTasks.offer(new PendingNoiseTask(originalOperation, proxy, isDependencyUnlock, map, holder));
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

        inflightCount.incrementAndGet();
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
        requestDrain();
        // bypass 队列清空后退出 bypass 模式
        if (bypassMode.get() && pendingNoiseTasks.isEmpty() && pendingCount.get() == 0) {
            bypassMode.set(false);
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
     * 两种模式：
     * <ul>
     *   <li>正常：获取组合 permit 后，通过 {@code resumeExecutor}（worldgen mailbox）
     *       异步提交原版操作，恢复回原执行上下文（P0-2）。</li>
     *   <li>bypass（调度器已禁用）：不获取 permit，每轮最多放行
     *       {@link #BYPASS_BATCH_PER_TICK} 个任务到原上下文（P1-3 有节奏恢复）。</li>
     * </ul>
     */
    private void drainOwnedPass() {
        // P0-4：紧急暂停时禁止启动任何普通任务
        if (admissionPaused) {
            return;
        }

        // P1-3：bypass 模式，有节奏放行（不获取 permit）
        if (bypassMode.get()) {
            int batch = 0;
            while (batch < BYPASS_BATCH_PER_TICK) {
                PendingNoiseTask task = pendingNoiseTasks.poll();
                if (task == null) {
                    return;
                }
                pendingCount.decrementAndGet();
                // 通过原 worldgen mailbox 提交，恢复原版线程语义（不获取 permit）
                task.resumeExecutor().execute(() ->
                        executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy(),
                                PermitLease.empty(), PermitLease.empty()));
                batch++;
            }
            return;
        }

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
            // 单一 drainer：peek 后 poll 必然取到同一任务（无并发 poller）
            PendingNoiseTask removed = pendingNoiseTasks.poll();
            if (removed != task) {
                // 防御性检查（正常不会发生）：释放 permit 重试
                stage.close();
                global.close();
                continue;
            }
            pendingCount.decrementAndGet();
            // P0-2：恢复通过原 worldgen mailbox 提交，回到原执行上下文，
            // 不直接在当前线程（可能为 Server Thread）调用 originalOperation。
            task.resumeExecutor().execute(() ->
                    executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy(), global, stage));
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
    /** P1-2：等待队列上限 */
    public int maxPendingNoiseTasks() { return maxPendingNoiseTasks; }
    public void setMaxPendingNoiseTasks(int max) { this.maxPendingNoiseTasks = Math.max(1, max); }
    /** P1-3：是否处于有节奏放行模式（调度器已禁用且队列未清空） */
    public boolean isBypassMode() { return bypassMode.get(); }

    /**
     * §9.4 服务器关闭或维度卸载时调用：异常完成所有等待任务并清空队列。
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
        PendingNoiseTask task;
        while ((task = pendingNoiseTasks.poll()) != null) {
            pendingCount.decrementAndGet();
            task.proxy().completeExceptionally(cause);
        }
        watchdog.clear();
        SteadyChunks.LOGGER.info("SteadyChunks 调度器已清空所有队列（等待任务异常完成: {}）", cause.getClass().getSimpleName());
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
