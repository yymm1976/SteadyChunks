package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightDiagnostics;
import com.mochi_753.steadychunks.diagnostics.inflight.TaskEventType;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.mixin.server.ChunkMapAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
    /**
     * 第 9 轮卡死修复：drain 成功 poll 计数（Watchdog 恢复线程用）。
     * drain 每处理一个排队任务递增——忙转死锁时 Server thread 卡在
     * processUnloads、drain 停滞，此值不变；恢复线程据此判定停摆。
     */
    private final AtomicLong drainProgress = new AtomicLong(0);
    public long drainProgress() { return drainProgress.get(); }
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
    /**
     * P0 修复（第 7 轮）：每维度生命周期状态（独立于全局 generation）。
     * 维度卸载时关闭该维度接收并递增维度代数，使已出队/已提交 mailbox 但未运行的
     * 任务在运行前被 {@link #lifecycleValid} 拒绝；不影响其他维度的任务。
     * {@link #openDimension} 在维度重新加载时恢复。
     */
    private final ConcurrentHashMap<ResourceKey<Level>, DimensionLifecycle> dimensionLifecycles = new ConcurrentHashMap<>();

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
    /** P2 修复（第 6 轮）：warning 状态切换节流标志（进入超阈值 WARN 一次/回落 INFO 一次） */
    private final AtomicBoolean pendingWarningActive = new AtomicBoolean(false);
    /** P2 修复（第 6 轮）：warning 持续状态摘要日志的最小间隔（毫秒） */
    private volatile long lastWarningSummaryMillis = 0L;
    /** P2 修复（第 6 轮）：warning 持续状态摘要日志间隔 */
    private static final long WARNING_SUMMARY_INTERVAL_MILLIS = 10_000L;

    /**
     * P0-1 修复（第 5 轮）：恢复执行器测试注入点。
     * 非 null 时替代 {@link PendingNoiseTask#resumeExecutor()}，用于 GameTest 模拟
     * mailbox 提交失败。生产环境保持 null（走原版 worldgen mailbox）。
     */
    private volatile Executor resumeExecutorOverride = null;
    // 第 8 轮 P1 修复：测试专用探针——enqueuePending 通过维度检查、读取维度代数之后、
    // 入队之前调用（默认 null，生产零开销）。GameTest 用它制造"维度检查后、offer 前
    // cancelDimension"的竞态窗口，验证入队后完整生命周期校验（含维度）。
    private volatile Runnable enqueueProbeHook = null;
    // 审查 P0-1（第 2 轮）修复：requeue 探针——第一次生命周期校验通过后、
    // offer 之前执行（复现 check-then-offer 竞态窗口；生产路径恒为 null）
    private volatile Runnable requeueProbeHook = null;

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

    /**
     * 审查修复：复位紧急/有节奏放行标志（bypass/failOpen）——GameTest fixture
     * 清洁断言用。setEnabled(false) 会置 bypassMode=true（有节奏放行积压队列），
     * 断言"bypassMode 未复位"因此必然失败；清理完成且队列已空时必须显式复位。
     */
    public void resetEmergencyFlags() {
        bypassMode.set(false);
        bypassBudget.set(0);
        failOpen.set(false);
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

        // 第 11 轮 P1 修复：全局停服门置于维度兼容 fail-open 之前——第三方替换
        // GeneratingChunkMap/Accessor 失效或维度解析暂时失败时，也不能绕过停服门
        // 直接执行（旧顺序先 dimensionOf（null → fail-open）再检查 accepting）。
        if (!acceptingTasks.get()
                || !LifecycleCleanupCoordinator.getInstance().isAcceptingRegistrations()) {
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 调度器已关闭"));
        }

        // P1 修复（第 7 轮）：无法从生成上下文解析维度时 fail-open 走原版。
        // 不抛异常（会破坏 Mixin 调用链），也不错误归入默认维度（卸载时无法定向取消、
        // 诊断归类错误）。正常实现为 ChunkMap，此分支仅防御第三方/测试替换实现。
        ResourceKey<Level> dimension = dimensionOf(map, holder);
        if (dimension == null) {
            return originalOperation.get();
        }

        // 第 9 轮 P0-1 修复：注册点前移到 NOISE 准入入口——direct（permit 立即满足）、
        // 排队（enqueuePending）与 fail-open（runUncontrolled）三条路径共享同一 lease，
        // 使 globalTaskCount/dimensionTaskCounts 覆盖全部受控 NOISE 任务（完整生命周期）。
        // 旧实现只在 enqueuePending 内注册：直接获准任务与 fail-open 透传不在计数中，
        // 停服等待 globalTaskCount 归零会漏掉正在运行的任务。注册失败（停服模式）：
        // 直接拒绝，不入队、不执行。
        LifecycleCleanupCoordinator.TaskRegistration registration =
                LifecycleCleanupCoordinator.getInstance().tryRegisterTask(dimension);
        if (!registration.registered()) {
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 服务器正在关闭"));
        }

        // 阶段 3：分配任务追踪 id（-1 = 追踪未启用；三条路径共享同一 id）。
        long traceTaskId = InflightDiagnostics.allocateTaskId();
        // 审查 P1 修复：三条路径统一携带真实维度/坐标（此前 direct/fail-open 传
        // null/MIN_VALUE，无法把 taskId 对应到 Holder 调查 generationRefCount）
        int traceChunkX = holder == null ? Integer.MIN_VALUE : holder.getPos().x;
        int traceChunkZ = holder == null ? Integer.MIN_VALUE : holder.getPos().z;

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
                return runUncontrolled(originalOperation, registration, traceTaskId, dimension, traceChunkX, traceChunkZ);
            }
        }
        if (pendingCount.get() >= pendingCriticalThreshold) {
            failOpen.set(true);
            SteadyChunks.LOGGER.warn("NOISE 等待队列超过紧急阈值 {}，进入 fail-open 透传（pending={}）",
                    pendingCriticalThreshold, pendingCount.get());
            return runUncontrolled(originalOperation, registration, traceTaskId, dimension, traceChunkX, traceChunkZ);
        }

        // P0-4 修复：紧急暂停时普通任务进入等待队列，依赖关键任务旁路放行
        if (admissionPaused && !isDependencyUnlock) {
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder, dimension, registration, traceTaskId);
        }

        // 组合 lease：固定获取顺序 global → stage
        PermitLease global = cpuGeneralPermit.tryAcquireLease();
        if (!global.acquired()) {
            // 全局 permit 不足：入队等待
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder, dimension, registration, traceTaskId);
        }
        PermitLease stage = stageLimiter.tryAcquireLease(targetStatus, isDependencyUnlock);
        if (!stage.acquired()) {
            // 阶段 permit 不足：释放全局 permit 后入队等待
            global.close();
            return enqueuePending(originalOperation, isDependencyUnlock, map, holder, dimension, registration, traceTaskId);
        }

        // 组合 permit 获取成功：执行原版操作，完成后统一释放（direct 路径共享准入入口的注册 lease）
        return executeOriginal(targetStatus, originalOperation, null, global, stage, registration,
                traceTaskId, dimension, traceChunkX, traceChunkZ);
    }

    /**
     * P1 修复（第 5 轮）：fail-open 透传执行原版操作，但统计非受控活动。
     * <p>
     * fail-open 不获取 permit（原版路径），但真实并发必须可观测：
     * {@code uncontrolledNoiseActive} 为当前透传数，{@code maxTotalNoiseActive}
     * 为受控 + 非受控总峰值，避免诊断指标低估。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> runUncontrolled(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            // 第 9 轮 P0-1 修复：fail-open 任务同样纳入完整生命周期计数——
            // 停服等待/维度泄漏检测不遗漏透传任务；原 Future 终态关闭 lease。
            LifecycleCleanupCoordinator.TaskRegistration registration,
            // 阶段 3：任务追踪 id（controlAdmission 分配）
            long traceTaskId,
            // 审查 P1 修复：fail-open 任务携带真实维度/坐标（此前 null/MIN_VALUE，
            // 而该类任务恰是"pending=0 但在途"的重点调查对象）
            ResourceKey<Level> traceDimension, int traceChunkX, int traceChunkZ) {
        int active = uncontrolledNoiseActive.incrementAndGet();
        // P1-4 修复（第 6 轮）：总并发峰值 = 受控 + 非受控之和
        updateTotalNoisePeak();
        InflightDiagnostics.record(traceTaskId, TaskEventType.EXECUTING, traceDimension, traceChunkX, traceChunkZ);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = originalOperation.get();
        } catch (Throwable ex) {
            uncontrolledNoiseActive.decrementAndGet();
            registration.close();
            // 审查 P0 修复：fail-open 无 proxy——lease 已手工关闭，终态在此发射
            // （REJECTED 后紧接 REGISTRATION_CLOSED + STEADY_STAGE_TERMINAL）
            InflightDiagnostics.record(traceTaskId, TaskEventType.REJECTED, traceDimension, traceChunkX, traceChunkZ);
            traceTerminalAfterClose(traceTaskId, traceDimension, traceChunkX, traceChunkZ);
            CompletableFuture<ChunkResult<ChunkAccess>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
        InflightDiagnostics.record(traceTaskId, TaskEventType.ORIGINAL_RETURNED, traceDimension, traceChunkX, traceChunkZ);
        future.whenComplete((result, ex) -> {
            uncontrolledNoiseActive.decrementAndGet();
            registration.close();
            InflightDiagnostics.record(traceTaskId, TaskEventType.ORIGINAL_COMPLETED, traceDimension, traceChunkX, traceChunkZ);
            traceTerminalAfterClose(traceTaskId, traceDimension, traceChunkX, traceChunkZ);
        });
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
            GenerationChunkHolder holder,
            ResourceKey<Level> dimension,
            // 第 9 轮 P0-1 修复：注册 lease 由准入入口（controlAdmission）创建并传入，
            // 本方法不再内部注册——direct/排队/fail-open 共享同一 lease，计数覆盖
            // 全部受控 NOISE 任务。所有拒绝路径必须关闭该 lease。
            LifecycleCleanupCoordinator.TaskRegistration registration,
            // 阶段 3：任务追踪 id（controlAdmission 分配；-1 = 未启用）
            long traceTaskId) {
        // P0 修复（第 4 轮）：生命周期停止接收时拒绝入队（停服/卸载场景，原版同步关闭中，
        // 返回 error result 等价于生成失败但不触发致命异常，不会残留永久等待的代理 Future）。
        if (!acceptingTasks.get()) {
            registration.close();
            // 审查 P0 修复：任务未创建（无 proxy 绑定）——lease 已手工关闭，
            // REJECTED 后紧接 REGISTRATION_CLOSED + STEADY_STAGE_TERMINAL
            InflightDiagnostics.record(traceTaskId, TaskEventType.REJECTED, dimension, Integer.MIN_VALUE, Integer.MIN_VALUE);
            traceTerminalAfterClose(traceTaskId, dimension, Integer.MIN_VALUE, Integer.MIN_VALUE);
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 调度器已停止接收任务"));
        }
        // P0 修复（第 7 轮）：维度卸载期间拒绝该维度新任务入队。
        DimensionLifecycle dimensionState =
                dimensionLifecycles.computeIfAbsent(dimension, ignored -> new DimensionLifecycle());
        if (!dimensionState.accepting.get()) {
            registration.close();
            InflightDiagnostics.record(traceTaskId, TaskEventType.REJECTED, dimension, Integer.MIN_VALUE, Integer.MIN_VALUE);
            traceTerminalAfterClose(traceTaskId, dimension, Integer.MIN_VALUE, Integer.MIN_VALUE);
            return CompletableFuture.completedFuture(
                    ChunkResult.error("SteadyChunks 维度正在卸载"));
        }
        long generation = lifecycleGeneration.get();
        long dimensionGeneration = dimensionState.generation.get();
        // 第 8 轮 P1 修复：测试专用探针（仅 GameTest 设置）——停在"维度检查已通过、
        // 尚未入队"的窗口，供测试在该窗口内 cancelDimension 制造竞态。
        Runnable probe = enqueueProbeHook;
        if (probe != null) {
            probe.run();
        }
        int depth = pendingCount.incrementAndGet();
        // P1-2：更新高水位（历史峰值，供诊断与报警）
        pendingHighWatermark.accumulateAndGet(depth, Math::max);
        // P2 修复（第 6 轮）：warning 日志状态切换节流——进入超阈值 WARN 一次、
        // 持续超阈值每 10 秒一条摘要、回落阈值以下 INFO 一次。避免队列从
        // 512 增长到 1024 时连续输出约五百条警告（洪峰）。
        if (depth > pendingWarningThreshold) {
            if (pendingWarningActive.compareAndSet(false, true)) {
                lastWarningSummaryMillis = System.currentTimeMillis();
                SteadyChunks.LOGGER.warn("NOISE 等待队列超告警阈值: depth={} warning={}（请检查 permit 配置或跑图速度）",
                        depth, pendingWarningThreshold);
            } else {
                long now = System.currentTimeMillis();
                if (now - lastWarningSummaryMillis >= WARNING_SUMMARY_INTERVAL_MILLIS) {
                    lastWarningSummaryMillis = now;
                    SteadyChunks.LOGGER.warn("NOISE 等待队列持续超告警阈值: depth={}（请检查 permit 配置或跑图速度）", depth);
                }
            }
        } else if (pendingWarningActive.getAndSet(false)) {
            SteadyChunks.LOGGER.info("NOISE 等待队列回落至告警阈值以下（pending={}）", depth);
        }
        CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
        // 第 9 轮 P1 修复：注册 lease 与代理 Future 终态统一绑定——此后所有终态路径
        // 只需 proxy.complete(...)，close 由本绑定自动触发（幂等 CAS 兜底）。
        // 旧实现八处手工 close：lease 关闭时机略早于代理终态（globalTaskCount 已归零
        // 但 proxy 未完成），且新增错误路径时易遗漏。
        // 审查 P0 修复：REGISTRATION_CLOSED + STEADY_STAGE_TERMINAL 严格在 lease
        // 关闭之后发射（同回调内顺序执行）——"活动表为空"等价于"注册 lease 已
        // 关闭"；拒绝/取消/恢复路径只经 proxy.complete 汇聚到此，不再各自发终态。
        proxy.whenComplete((result, ex) -> {
            registration.close();
            traceTerminalAfterClose(traceTaskId, dimension,
                    holder == null ? Integer.MIN_VALUE : holder.getPos().x,
                    holder == null ? Integer.MIN_VALUE : holder.getPos().z);
        });
        PendingNoiseTask task = new PendingNoiseTask(
                originalOperation, proxy, isDependencyUnlock, map, holder,
                dimension, generation, dimensionGeneration, registration, traceTaskId);
        // 阶段 3：创建/入队事件（坐标取 holder 第一现场）
        InflightDiagnostics.record(traceTaskId, TaskEventType.CREATED, dimension,
                holder == null ? Integer.MIN_VALUE : holder.getPos().x,
                holder == null ? Integer.MIN_VALUE : holder.getPos().z);
        pendingNoiseTasks.offer(task);
        InflightDiagnostics.record(traceTaskId, TaskEventType.ADMITTED, dimension,
                holder == null ? Integer.MIN_VALUE : holder.getPos().x,
                holder == null ? Integer.MIN_VALUE : holder.getPos().z);
        // 第 8 轮 P1 修复：入队后二次校验完整生命周期（全局 + 维度），替代旧实现只复查
        // 全局 lifecycleGeneration。旧实现漏掉"维度检查后、offer 前 cancelDimension"窗口：
        // 维度已卸载但任务仍以旧维度代数入队，且全局代数未变、二次检查通过。
        // 若清理已在检查与入队之间发生，移除任务并以 error result 正常完成
        // （异常完成会让原版 setFatalException，破坏真实区块生成链），避免残留；
        // proxy 完成触发上方绑定，lease 自动关闭。
        if (!lifecycleValid(task)) {
            if (pendingNoiseTasks.remove(task)) {
                pendingCount.updateAndGet(v -> Math.max(0, v - 1));
                proxy.complete(ChunkResult.error("SteadyChunks 调度器生命周期已变化"));
                // 审查 P1 修复：终态事件只由成功取得终态所有权的路径记录——
                // remove 失败（drainer 已并发 poll）时不得在此发终态（任务由
                // drainer 路径最终完成，绑定回调统一发射）；REJECTED 为诊断事件，
                // 在 remove 之外记录（校验失败本身成立）
            }
            InflightDiagnostics.record(traceTaskId, TaskEventType.REJECTED, dimension,
                    holder == null ? Integer.MIN_VALUE : holder.getPos().x,
                    holder == null ? Integer.MIN_VALUE : holder.getPos().z);
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
            PermitLease stage,
            // 第 9 轮 P0-1/P1 修复：注册 lease 由准入入口（controlAdmission）创建，
            // direct 与排队路径共享（direct 不再传 null）；本方法内与终态 Future
            // 统一绑定（proxy 或原 Future 的 whenComplete → close），此后所有终态
            // 路径只需完成 proxy，不再手工 close。
            LifecycleCleanupCoordinator.TaskRegistration registration,
            // 阶段 3：任务追踪 id（-1 = 未启用）
            long traceTaskId,
            // 审查 P1 修复：direct/排队路径统一携带真实维度/坐标（此前 direct 传
            // null/MIN_VALUE，无法把 taskId 对应到 Holder 做 refCount 调查）
            ResourceKey<Level> traceDimension, int traceChunkX, int traceChunkZ) {

        // P1 修复（第 4 轮）：记录 NOISE 在途任务峰值（真实生成测试验证并发上限）。
        int active = inflightCount.incrementAndGet();
        maxActiveNoise.accumulateAndGet(active, Math::max);
        // P1-4 修复（第 6 轮）：总并发峰值 = 受控 + 非受控之和
        updateTotalNoisePeak();
        InflightDiagnostics.record(traceTaskId, TaskEventType.EXECUTING, traceDimension, traceChunkX, traceChunkZ);
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = originalOperation.get();
        } catch (Throwable ex) {
            // 第 10 轮 P0-1 修复：同步异常统一失败路径——释放组合 permit 并回退
            // inflightCount（旧实现只关 registration，一次同步异常永久消耗一个全局
            // permit + 一个 NOISE permit + 一个 inflightCount，重复几次后所有 NOISE
            // 都进等待队列）。释放顺序与获取顺序相反：stage → global。
            stage.close();
            global.close();
            inflightCount.decrementAndGet();
            // direct 异常路径无 proxy 可触发终态绑定，手工关闭 lease
            // （排队路径 proxy.completeExceptionally 会触发绑定，无需此处关闭）。
            if (proxy == null && registration != null) {
                registration.close();
            }
            // 审查 P0 修复：REJECTED 后——direct 路径 lease 已手工关闭，终态在此
            // 发射；排队路径由 proxy.completeExceptionally 触发绑定回调发射
            InflightDiagnostics.record(traceTaskId, TaskEventType.REJECTED, traceDimension, traceChunkX, traceChunkZ);
            if (proxy == null) {
                traceTerminalAfterClose(traceTaskId, traceDimension, traceChunkX, traceChunkZ);
            }
            requestDrain();
            if (proxy != null) {
                proxy.completeExceptionally(ex);
                return proxy;
            }
            CompletableFuture<ChunkResult<ChunkAccess>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
        InflightDiagnostics.record(traceTaskId, TaskEventType.ORIGINAL_RETURNED, traceDimension, traceChunkX, traceChunkZ);

        // 第 9 轮 P1 修复：注册 lease 与任务终态 Future 统一绑定——排队路径绑定代理
        // Future（此后所有终态路径只需 proxy.complete），direct 路径绑定原 Future。
        // close 严格发生在任务对外可见终态之后（proxy.complete 同步触发绑定回调），
        // 消除"计数已归零但代理未终态"的窗口；幂等 CAS 仍作为最后保护。
        // 审查 P0 修复：direct 路径无 enqueuePending 绑定——终态事件（REGISTRATION_
        // CLOSED + STEADY_STAGE_TERMINAL）在 lease 关闭后在此发射；排队路径的事件
        // 由 enqueuePending 的 proxy 绑定回调发射（此处只 close，避免重复终态）。
        if (registration != null) {
            CompletableFuture<ChunkResult<ChunkAccess>> terminal =
                    proxy != null ? proxy : future;
            if (proxy == null) {
                terminal.whenComplete((result, ex) -> {
                    registration.close();
                    traceTerminalAfterClose(traceTaskId, traceDimension, traceChunkX, traceChunkZ);
                });
            } else {
                terminal.whenComplete((result, ex) -> registration.close());
            }
        }

        future.whenComplete((result, ex) -> {
            // 释放顺序与获取顺序相反：stage → global
            stage.close();
            global.close();
            inflightCount.decrementAndGet();
            // 审查 P0 修复：终态事件拆分与顺序——原 Future 终态 → permit 释放 →
            // 代理完成尝试 → [proxy.complete 同步触发绑定回调：registration close
            // + REGISTRATION_CLOSED + STEADY_STAGE_TERMINAL] → 代理终态确认。
            // STEADY_STAGE_TERMINAL 不早于 registration close（由绑定回调发射）。
            InflightDiagnostics.record(traceTaskId, TaskEventType.ORIGINAL_COMPLETED, traceDimension, traceChunkX, traceChunkZ);
            InflightDiagnostics.record(traceTaskId, TaskEventType.PERMITS_RELEASED, traceDimension, traceChunkX, traceChunkZ);
            if (proxy != null) {
                // 完成代理 Future（完成顺序：先释放 permit，再完成 proxy——
                // proxy 完成时触发上方绑定回调关闭注册 lease）
                InflightDiagnostics.record(traceTaskId, TaskEventType.PROXY_COMPLETE_ATTEMPT, traceDimension, traceChunkX, traceChunkZ);
                if (ex != null) {
                    proxy.completeExceptionally(ex);
                } else {
                    proxy.complete(result);
                }
                // complete 返回后（绑定回调已同步执行：registration close + 终态）
                InflightDiagnostics.record(traceTaskId, TaskEventType.PROXY_COMPLETED, traceDimension, traceChunkX, traceChunkZ);
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
            drainProgress.incrementAndGet();
            // 第 8 轮 P0 修复（方案 B）：任务离开等待队列不注销注册 lease——
            // 计数覆盖完整任务生命周期，直到代理 Future 终态（executeOriginal
            // whenComplete / completeLifecycleRejected / mailbox 失败路径）才关闭。
            // 通过原 worldgen mailbox 提交，恢复原版线程语义（不获取 permit）。
            // P0-1 修复（第 5 轮）：提交失败由 submitResumed 统一兜底。
            InflightDiagnostics.record(task.traceTaskId(), TaskEventType.DEQUEUED, task.dimension(),
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x,
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z);
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
            drainProgress.incrementAndGet();
            // 第 8 轮 P0 修复（方案 B）：任务离开等待队列不注销注册 lease——
            // 计数覆盖完整任务生命周期，直到代理 Future 终态才关闭（同上）。
            // P0-2：恢复通过原 worldgen mailbox 提交，回到原执行上下文，
            // 不直接在当前线程（可能为 Server Thread）调用 originalOperation。
            InflightDiagnostics.record(removed.traceTaskId(), TaskEventType.DEQUEUED, removed.dimension(),
                    removed.holder() == null ? Integer.MIN_VALUE : removed.holder().getPos().x,
                    removed.holder() == null ? Integer.MIN_VALUE : removed.holder().getPos().z);
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
        // P0-2 修复（第 6 轮）：提交前生命周期校验（覆盖"poll 出队后"与"提交 mailbox 前"）。
        // 任务出队后若 closeForShutdown/resetForReload 已发生（generation 变化或停止接收），
        // 拒绝恢复执行，避免"已出队但未提交"（ADMITTED_NOT_SUBMITTED）的任务在关闭后仍启动。
        if (!lifecycleValid(task)) {
            completeLifecycleRejected(task, global, stage);
            return;
        }
        try {
            Executor executor = resumeExecutorOverride != null ? resumeExecutorOverride : task.resumeExecutor();
            // 阶段 3：提交事件（进入 executor 队列前）
            InflightDiagnostics.record(task.traceTaskId(), TaskEventType.SUBMITTED, task.dimension(),
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x,
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z);
            executor.execute(() -> {
                // 阶段 3：mailbox runnable 开始运行（生命周期二次校验之前）
                InflightDiagnostics.record(task.traceTaskId(), TaskEventType.MAILBOX_STARTED, task.dimension(),
                        task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x,
                        task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z);
                // P0-2 修复（第 6 轮）：mailbox Runnable 真正运行前再次校验。
                // mailbox 排队期间关闭/重置可能已发生，此时拒绝执行原版操作。
                if (!lifecycleValid(task)) {
                    completeLifecycleRejected(task, global, stage);
                    return;
                }
                executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy(), global, stage,
                        task.registration(), task.traceTaskId(), task.dimension(),
                        task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x,
                        task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z);
            });
        } catch (RejectedExecutionException exception) {
            // P0-1 修复（第 6 轮）：预期的生命周期拒绝（mailbox 停止接收）→ error result 正常完成。
            // 异常完成会被原版 lambda$applyStep$0 视为致命（MinecraftServer.setFatalException），
            // 与第 5 轮"取消/清理路径统一 error result"的语义保持一致。
            stage.close();
            global.close();
            // 第 9 轮 P1 修复：不再手工 close——proxy.complete 触发终态绑定自动关闭
            task.proxy().complete(ChunkResult.error("SteadyChunks worldgen mailbox 已停止接收"));
            traceRejected(task);
            requestDrain();
        } catch (Throwable throwable) {
            // P0-1 修复（第 6 轮）：非预期错误 → 记录日志 + error result 正常完成（同样避免 fatal）。
            stage.close();
            global.close();
            // 第 9 轮 P1 修复：不再手工 close——proxy.complete 触发终态绑定自动关闭
            SteadyChunks.LOGGER.error("提交 NOISE 恢复任务时发生非预期错误", throwable);
            task.proxy().complete(ChunkResult.error(
                    "SteadyChunks 无法恢复 NOISE 任务: " + throwable.getClass().getSimpleName()));
            traceRejected(task);
            requestDrain();
        }
    }

    /**
     * P0-2 修复（第 6 轮）+ P0（第 7 轮）：生命周期有效性判断。
     * <p>
     * 第 7 轮起同时校验全局与维度两层生命周期：
     * <ul>
     *   <li>全局：调度器仍在接收任务且任务入队代数未过期（closeForShutdown/resetForReload）；</li>
     *   <li>维度：维度仍在接收且任务入队时的维度代数未过期（维度卸载 cancelDimension 会
     *       关闭该维度接收并递增维度代数）。</li>
     * </ul>
     * 这样维度卸载发生在"任务已 poll 出队/已提交 mailbox 但未运行"时，任务仍会被拒绝，
     * 不依赖全局 generation（避免连带取消其他维度的任务）。
     */
    private boolean lifecycleValid(PendingNoiseTask task) {
        if (!acceptingTasks.get() || task.globalGeneration() != lifecycleGeneration.get()) {
            return false;
        }
        DimensionLifecycle state = dimensionLifecycles.get(task.dimension());
        return state != null && state.accepting.get()
                && task.dimensionGeneration() == state.generation.get();
    }

    /**
     * P0-2 修复（第 6 轮）：生命周期变化导致任务被拒绝时：释放组合 permit + error result 正常完成。
     */
    private void completeLifecycleRejected(PendingNoiseTask task, PermitLease global, PermitLease stage) {
        stage.close();
        global.close();
        // 第 9 轮 P1 修复：不再手工 close——proxy.complete 触发终态绑定自动关闭
        task.proxy().complete(ChunkResult.error("SteadyChunks 调度器生命周期已变化，任务被拒绝"));
        traceRejected(task);
        requestDrain();
    }

    // ---- 阶段 3：在途任务追踪辅助（值类型事件，无强引用） ----
    // 审查 P0 修复：STEADY_STAGE_TERMINAL 只由 registration close 绑定回调发射
    // （queued：enqueuePending proxy 绑定；direct/fail-open：原 Future 绑定或
    // 手工 close 路径）——本组辅助方法只记录过程事件；拒绝/取消/恢复路径经
    // proxy.complete 汇聚到绑定回调统一发终态（每 taskId 唯一）。
    private static void traceRejected(PendingNoiseTask task) {
        int x = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x;
        int z = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z;
        InflightDiagnostics.record(task.traceTaskId(), TaskEventType.REJECTED, task.dimension(), x, z);
    }

    private static void traceCancelled(PendingNoiseTask task, TaskEventType type) {
        int x = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x;
        int z = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z;
        InflightDiagnostics.record(task.traceTaskId(), type, task.dimension(), x, z);
    }

    /** 终态事件：REGISTRATION_CLOSED + STEADY_STAGE_TERMINAL——只在 lease 关闭后调用。 */
    private static void traceTerminalAfterClose(long traceTaskId, ResourceKey<Level> dimension,
            int chunkX, int chunkZ) {
        InflightDiagnostics.record(traceTaskId, TaskEventType.REGISTRATION_CLOSED, dimension, chunkX, chunkZ);
        InflightDiagnostics.record(traceTaskId, TaskEventType.STEADY_STAGE_TERMINAL, dimension, chunkX, chunkZ);
    }

    /**
     * P1-4 修复（第 6 轮）：更新 NOISE 总活动峰值（受控 + 非受控之和）。
     * 受控任务（executeOriginal）与非受控任务（runUncontrolled）开始执行时都调用，
     * 避免 maxTotalNoiseActive 只记录非受控数量而低估真实并发。
     */
    private void updateTotalNoisePeak() {
        int total = inflightCount.get() + uncontrolledNoiseActive.get();
        maxTotalNoiseActive.accumulateAndGet(total, Math::max);
    }

    /**
     * P1 修复（第 7 轮）：从任务所属的生成上下文提取维度（用于维度级定向取消）。
     * <p>
     * 优先从 map（实际实现为 {@link ChunkMap}）取 level：真实 NOISE applyStep 阶段
     * holder 尚未持有 chunk（{@code getLatestChunk()} 返回 null），ChunkMap 的 level
     * 始终可用。非 ChunkMap 实现回退到 holder 的 chunk。无法识别时返回 {@code null}
     * （调用方 fail-open 走原版）——不抛异常（破坏 Mixin 调用链）、也不错误归入默认维度。
     */
    private static ResourceKey<Level> dimensionOf(GeneratingChunkMap map, GenerationChunkHolder holder) {
        if (map instanceof ChunkMap) {
            ServerLevel level = ((ChunkMapAccessor) map).steady$level();
            if (level != null) {
                return level.dimension();
            }
        }
        if (holder != null) {
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk != null && chunk.getLevel() != null) {
                return chunk.getLevel().dimension();
            }
        }
        return null;
    }

    /**
     * P1-1 修复（第 6/7 轮）：维度卸载时定向取消该维度的所有等待任务。
     * <p>
     * 第 7 轮修复：先关闭该维度生命周期（{@code accepting=false} + 递增维度代数），
     * 使<b>已 poll 出队 / 已提交 mailbox 但尚未运行</b>的任务在运行前被
     * {@link #lifecycleValid} 拒绝（该窗口不依赖全局 generation，避免连带取消
     * 其他维度的任务）。仍在等待队列中的任务被直接移除并以 error result 正常完成
     * （避免 fatal）。已进入 {@code executeOriginal} 的任务不强制中断，自然结束。
     *
     * @param dimension 目标维度
     * @param reason    取消原因（error result 文案）
     */
    public void cancelDimension(ResourceKey<Level> dimension, String reason) {
        DimensionLifecycle state =
                dimensionLifecycles.computeIfAbsent(dimension, ignored -> new DimensionLifecycle());
        state.accepting.set(false);
        state.generation.incrementAndGet();
        pendingNoiseTasks.removeIf(task -> {
            if (!dimension.equals(task.dimension())) {
                return false;
            }
            pendingCount.updateAndGet(v -> Math.max(0, v - 1));
            // 第 9 轮 P1 修复：不再手工 close——proxy.complete 触发终态绑定自动关闭
            task.proxy().complete(ChunkResult.error(reason));
            traceCancelled(task, TaskEventType.CANCELLED);
            return true;
        });
        requestDrain();
    }

    /**
     * P0 修复（第 7 轮）：维度重新加载时恢复该维度的生命周期（维度加载事件调用）。
     * <p>
     * 递增维度代数使卸载前入队、已出队但未运行的任务全部失效（运行前被拒绝）；
     * 恢复接收标志允许该维度新任务入队。
     */
    public void openDimension(ResourceKey<Level> dimension) {
        DimensionLifecycle state =
                dimensionLifecycles.computeIfAbsent(dimension, ignored -> new DimensionLifecycle());
        state.generation.incrementAndGet();
        state.accepting.set(true);
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
    // 第 8 轮 P1 修复：测试专用探针（GameTest 制造入队竞态窗口，生产不设置）
    public void setEnqueueProbeHook(Runnable probe) { this.enqueueProbeHook = probe; }
    /** 测试注入的 requeue 探针（阶段 2：清洁断言用；生产 null）。 */
    public void setRequeueProbeHook(Runnable probe) { this.requeueProbeHook = probe; }
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

    /** 测试注入的恢复执行器当前值（阶段 2：清洁断言用；生产 null）。 */
    public Executor resumeExecutorOverride() { return resumeExecutorOverride; }

    /** 测试注入的入队探针当前值（阶段 2：清洁断言用；生产 null）。 */
    public Runnable enqueueProbeHook() { return enqueueProbeHook; }

    /** 测试注入的 requeue 探针当前值（阶段 2：清洁断言用；生产 null）。 */
    public Runnable requeueProbeHook() { return requeueProbeHook; }

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
     * 第 9 轮 P1 修复：仅恢复任务接收，<b>不清空等待队列</b>（服务器启动场景专用）。
     * <p>
     * {@link #resetForReload(Throwable)} 会以 error result 清空队列——服务器启动
     * （ServerStarting）时队列可能仍有 spawn 区域真实生成残留任务在排空，被清空后
     * 这些区块卡在 NOISE 之前；随后任何强制同步加载（如信标 BE tick 的
     * getBlockState → Level.getChunk 死等未达 FULL 的区块）会在 Server thread 上
     * 自我死锁（drain 依赖 Server thread tick 推进）。启动场景应让残留任务自然完成，
     * 仅恢复接收。测试/运行期 reload 仍使用 {@link #resetForReload(Throwable)}。
     */
    public void resumeAccepting() {
        acceptingTasks.set(true);
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
     * 第 11 轮 P0 修复：mailbox 恢复批次（第一级保留任务引用，第二级可强制完成）。
     * <p>
     * 旧实现第一级把任务 poll 出队后仅提交 mailbox：若 mailbox 停滞，任务从队列
     * 消失（pendingCount 归零）但 proxy 永不完成——Watchdog 因 pending==0 早退，
     * 第二级对空队列也找不到任务。批次持久化任务引用直到 proxy 终态：
     * Watchdog 保存 {@code activeRecovery}，超时后对批次内未完成任务直接
     * complete（UNSAFE_EMERGENCY）。
     * <p>
     * 第 13 轮 P0 修复（窗口 A）：捕获与提交拆分为两步——{@link #captureRecoveryBatch()}
     * 仅从队列移出任务（调用方锁内捕获后<b>立即发布</b>），{@link #submitRecoveryBatch}
     * 在批次已发布的前提下锁外逐个提交 mailbox。旧版 beginMailboxRecovery 先逐个
     * execute 全部返回后才形成批次：executor 阻塞时任务已离队但批次未发布，
     * 停服/第二级都看不到（批次所有权不可见窗口）。
     * <p>
     * public 供 GameTest 断言批次行为（tasks 组件为包私有类型，GameTest 只经
     * {@link ChunkScheduler#escalateRecoveryBatch(RecoveryBatch)} 交互）。
     */
    public record RecoveryBatch(long id, long deadlineNanos, List<PendingNoiseTask> tasks) {
    }

    /** 恢复批次 ID 来源 */
    private final AtomicLong recoveryBatchId = new AtomicLong(0);
    /** 第一级提交后等待 mailbox 执行的期限（纳秒），超时升级第二级 */
    private static final long RECOVERY_WAIT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);

    /**
     * 第 13 轮 P0 修复：drain 停摆恢复第一级第一步——仅从 pending 移出任务形成
     * {@link RecoveryBatch}，<b>不调用任何 executor</b>。
     * <p>
     * 调用方（Watchdog）必须在锁内完成"捕获 → 发布 activeRecovery"（零间隙），
     * 再锁外调用 {@link #submitRecoveryBatch(RecoveryBatch)}——保证 executor 阻塞
     * 时批次对停服/第二级始终可见（窗口 A 修复，旧 beginMailboxRecovery 先提交
     * 后返回批次，提交期间任务所有权不可见）。
     */
    public RecoveryBatch captureRecoveryBatch() {
        List<PendingNoiseTask> tasks = new java.util.ArrayList<>();
        PendingNoiseTask task;
        while ((task = pendingNoiseTasks.poll()) != null) {
            pendingCount.updateAndGet(v -> Math.max(0, v - 1));
            // 阶段 3：被恢复批次捕获（离开队列，引用保留在批次中直至终态）
            InflightDiagnostics.record(task.traceTaskId(), TaskEventType.RECOVERY_CAPTURED, task.dimension(),
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x,
                    task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z);
            tasks.add(task);
        }
        return new RecoveryBatch(recoveryBatchId.incrementAndGet(),
                System.nanoTime() + RECOVERY_WAIT_NANOS, tasks);
    }

    /**
     * 第 13 轮 P0 修复：drain 停摆恢复第一级第二步——对<b>已发布</b>的批次逐任务
     * 经原 worldgen mailbox 提交 error completion（保持原版线程语义；调用方须在
     * 批次可见后锁外调用，批次可见性由调用方保证）。
     * <p>
     * mailbox 立即拒绝（execute 抛异常）的任务在此直接 complete（与
     * UNSAFE_EMERGENCY 同线程语义），计数返回由 Watchdog 计入 unsafe 指标
     * （第 12 轮 P2 修复：仅 complete 成功者计数——已被并发路径完成的不得重复计）。
     *
     * @return 本次实际直接完成的拒绝任务数
     */
    public int submitRecoveryBatch(RecoveryBatch batch) {
        int rejected = 0;
        for (PendingNoiseTask t : batch.tasks()) {
            // 审查 P1（第 2 轮）修复：任务已终态（第二级/停服/并发路径已处置）
            // 则跳过提交——避免迟到提交对已终态任务重复 execute/complete
            if (t.proxy().isDone()) {
                continue;
            }
            try {
                Executor executor = resumeExecutorOverride != null ? resumeExecutorOverride : t.resumeExecutor();
                executor.execute(() -> {
                    // 阶段 3：恢复处置完成（mailbox 提交执行 → 终态）
                    if (t.proxy().complete(
                            ChunkResult.error("SteadyChunks drain 停摆恢复（mailbox）"))) {
                        traceRecovered(t);
                    }
                });
            } catch (Throwable ex) {
                // mailbox 拒绝（停滞/关闭）：daemon 线程直接完成，计入 unsafe 指标
                if (t.proxy().complete(ChunkResult.error("SteadyChunks drain 停摆恢复（mailbox 拒绝降级）"))) {
                    rejected++;
                    traceRecovered(t);
                }
            }
        }
        return rejected;
    }

    // ---- 阶段 3：恢复处置终态追踪辅助 ----
    private static void traceRecovered(PendingNoiseTask task) {
        int x = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().x;
        int z = task.holder() == null ? Integer.MIN_VALUE : task.holder().getPos().z;
        InflightDiagnostics.record(task.traceTaskId(), TaskEventType.RECOVERY_COMPLETED, task.dimension(), x, z);
    }

    /**
     * 第 14 轮 P1 修复：捕获后因停服/换代放弃的批次任务重新入队。
     * <p>
     * Watchdog 先发布 CAPTURING 所有权再锁外捕获；捕获期间 stopRecoveryThread
     * 可能已摘除批次（此时无任务可处置）——捕获到的任务必须回退队列，
     * 由停服的 closeForShutdown 兜底清理（或新 Watchdog 重新恢复），不得遗弃。
     * proxy 未完成、lease 未关闭，与普通等待任务语义一致。
     * <p>
     * 审查 P0-1 修复：回退前逐任务完整生命周期校验——capture 期间若
     * closeForShutdown/resetForReload 已执行（accepting=false、全局/维度代数
     * 已递增），重新入队会产生停服后的孤儿 proxy（队列无人消费）。
     * 无效任务直接 error 完成（proxy 终态绑定自动关闭 lease），与
     * {@link #completeLifecycleRejected} 同语义。
     * <p>
     * 审查 P0-1（第 2 轮）修复：首次校验与 offer 之间仍存在 check-then-offer
     * 窗口——requeue 通过校验后、offer 前 closeForShutdown 已清空队列并停止
     * 消费，任务以旧生命周期入队即孤儿。offer 后二次校验（同
     * {@link #enqueuePending} 模式）：remove 成功者 error 完成；remove 失败
     * （drainer 已并发 poll）则任务由 drainer 路径完成，不重复 complete。
     */
    public void requeueRecoveryBatch(RecoveryBatch batch) {
        for (PendingNoiseTask t : batch.tasks()) {
            if (!acceptingTasks.get() || !lifecycleValid(t)) {
                // 停服/换代后不得重新入队：error 完成（不触发 fatal）
                t.proxy().complete(ChunkResult.error("SteadyChunks 调度器已关闭，恢复批次任务无法重新入队"));
                traceRejected(t);
                continue;
            }
            Runnable probe = requeueProbeHook;
            if (probe != null) {
                probe.run();
            }
            pendingNoiseTasks.offer(t);
            pendingCount.incrementAndGet();
            if (!lifecycleValid(t)) {
                if (pendingNoiseTasks.remove(t)) {
                    pendingCount.updateAndGet(v -> Math.max(0, v - 1));
                    t.proxy().complete(ChunkResult.error("SteadyChunks 调度器生命周期已变化"));
                }
                traceRejected(t);
            }
        }
        requestDrain();
    }

    /** 批次内所有任务的代理 Future 是否全部终态（Watchdog 批次状态机用）。 */
    public boolean recoveryBatchAllDone(RecoveryBatch batch) {
        for (PendingNoiseTask t : batch.tasks()) {
            if (!t.proxy().isDone()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 审查 P0-4 修复：批次全部 proxy 终态的 Future（停服批次生命周期用）。
     * <p>
     * 批次移除必须绑定所有 proxy 的终态，而非"谁成功调用了 complete"——
     * mailbox 抢先完成时 complete 返回 false、完成回调不触发；部分 emergency
     * 完成、其余由 mailbox 稍后完成时同样无人再检查。allOf 挂到全部终态，
     * 无论谁完成必然触发；空批次立即完成。
     */
    public CompletableFuture<Void> recoveryBatchAllDoneFuture(RecoveryBatch batch) {
        CompletableFuture<?>[] proxies = batch.tasks().stream()
                .map(t -> (CompletableFuture<?>) t.proxy())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(proxies);
    }

    /**
     * 第 11 轮 P0 修复：drain 停摆恢复第二级（UNSAFE_EMERGENCY）——对批次内
     * 尚未终态的任务由调用线程（Watchdog daemon）直接 complete。complete 返回
     * false 表示任务已被其他路径完成（幂等），不计入 unsafe。
     *
     * @return 本次实际直接完成的任务数（unsafe 计数）
     */
    public int escalateRecoveryBatch(RecoveryBatch batch) {
        return escalateRecoveryBatch(batch, "SteadyChunks drain 停摆恢复（UNSAFE_EMERGENCY）");
    }

    /**
     * 第 12 轮 P0 修复：带原因的第二级——停服路径（Watchdog.stopRecoveryThread 处置
     * 活动批次）复用同一强制完成逻辑，仅 error result 信息区分来源。
     *
     * @return 本次实际直接完成的任务数（unsafe 计数）
     */
    public int escalateRecoveryBatch(RecoveryBatch batch, String reason) {
        int unsafe = 0;
        for (PendingNoiseTask t : batch.tasks()) {
            if (t.proxy().complete(ChunkResult.error(reason))) {
                unsafe++;
                traceRecovered(t);
            }
        }
        return unsafe;
    }

    /**
     * 第 14 轮 P0-3 修复：异步强制完成——逐任务提交到指定 executor（每任务独立
     * 线程），complete 成功者经 {@code onCompleted} 回调计数（并发调用，调用方
     * 累加须线程安全）。同步回调阻塞只卡自己的执行线程，不阻塞调用方
     * （ServerStopping）；complete 幂等（已被并发完成的返回 false 不计数）。
     *
     * @param batch       目标批次
     * @param reason      error result 原因
     * @param executor    每任务独立线程的执行器（如虚拟线程执行器）
     * @param onCompleted 每次实际完成的回调（参数恒为 1，可累加）
     */
    public void escalateRecoveryBatchAsync(RecoveryBatch batch, String reason,
            Executor executor, java.util.function.IntConsumer onCompleted) {
        for (PendingNoiseTask t : batch.tasks()) {
            executor.execute(() -> {
                if (t.proxy().complete(ChunkResult.error(reason))) {
                    onCompleted.accept(1);
                    traceRecovered(t);
                }
            });
        }
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
            // 第 9 轮 P1 修复：不再手工 close——proxy.complete 触发终态绑定自动关闭
            // P2 修复（第 5 轮）：以 error result 正常完成（而非异常完成）。
            // 原版 GenerationChunkHolder.lambda$applyStep$0 的 handle 会把异常完成视为
            // 致命错误（MinecraftServer.setFatalException），导致真实区块生成链中断、
            // 区块卡在生成中无法卸载（processUnloads 忙转）。error result 走 completeFuture
            // 正常路径，区块状态可恢复，不会卡死。
            task.proxy().complete(ChunkResult.error("SteadyChunks 调度器清理: " + cause));
            traceCancelled(task, TaskEventType.CANCELLED);
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
            GenerationChunkHolder holder,
            // P1-1 修复（第 6 轮）：任务所属维度，用于维度级定向取消（cancelDimension）。
            ResourceKey<Level> dimension,
            // P0-2 修复（第 6 轮）：任务入队时的全局生命周期代数。出队/提交/运行前校验
            // 代数未变化，关闭或重置后拒绝执行，防止已出队任务在关闭后启动。
            long globalGeneration,
            // P0 修复（第 7 轮）：任务入队时的维度生命周期代数。维度卸载会递增维度代数
            // 并关闭接收，已出队/已提交但未运行的任务经 lifecycleValid 拒绝（不依赖全局）。
            long dimensionGeneration,
            // 第 9 轮 P0-1/P1 修复：注册 lease 由准入入口（controlAdmission）创建，
            // 经本 record 从入队持有到代理 Future 终态——排队路径在 enqueuePending
            // 绑定 proxy.whenComplete → close；poll 出队不注销，终态统一由绑定触发。
            LifecycleCleanupCoordinator.TaskRegistration registration,
            // 阶段 3：任务追踪 id（InflightDiagnostics 分配；-1 = 未启用）
            long traceTaskId
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

    /**
     * P0 修复（第 7 轮）：单维度生命周期状态。
     * <p>
     * {@code accepting} 表示维度是否接收新任务（维度卸载置 false，重新加载置 true）；
     * {@code generation} 为维度代数，每次卸载/重新加载递增，使旧代数的任务失效。
     */
    private static final class DimensionLifecycle {
        final AtomicLong generation = new AtomicLong();
        final AtomicBoolean accepting = new AtomicBoolean(true);
    }
}
