package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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
     */
    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks 调度器: {}", on ? "enabled" : "disabled");
        // 禁用时唤醒所有等待任务（走原版路径）
        if (!on) {
            drainPendingForce();
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
            // 恢复时唤醒等待队列
            if (!paused) {
                drainPending();
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
     * @param originalOperation  原版 applyStep 操作（返回原版 Future）
     * @return 代理 Future 或原版 Future
     */
    public CompletableFuture<ChunkResult<ChunkAccess>> controlAdmission(
            ChunkStatus targetStatus,
            boolean isDependencyUnlock,
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
            return enqueuePending(originalOperation, isDependencyUnlock);
        }

        // 组合 lease：固定获取顺序 global → stage
        PermitLease global = cpuGeneralPermit.tryAcquireLease();
        if (!global.acquired()) {
            // 全局 permit 不足：入队等待
            return enqueuePending(originalOperation, isDependencyUnlock);
        }
        PermitLease stage = stageLimiter.tryAcquireLease(targetStatus, isDependencyUnlock);
        if (!stage.acquired()) {
            // 阶段 permit 不足：释放全局 permit 后入队等待
            global.close();
            return enqueuePending(originalOperation, isDependencyUnlock);
        }

        // 组合 permit 获取成功：执行原版操作，完成后统一释放
        return executeOriginal(targetStatus, originalOperation, null, global, stage);
    }

    /**
     * 创建代理 Future 并将任务放入等待队列。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> enqueuePending(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            boolean isDependencyUnlock) {
        CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
        pendingNoiseTasks.offer(new PendingNoiseTask(originalOperation, proxy, isDependencyUnlock));
        pendingCount.incrementAndGet();
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
            drainPending();
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
            // 尝试唤醒等待队列（回调运行于原版任务完成线程 = worldgen 上下文）
            drainPending();
        });

        return proxy != null ? proxy : future;
    }

    /**
     * 每 tick 调用：处理等待队列中的 NOISE 任务。
     */
    public void tick() {
        if (!enabled.get()) {
            return;
        }
        tickCounter++;
        watchdog.tick(tickCounter, this);
        // 处理等待队列
        drainPending();
    }

    /**
     * 尝试执行等待队列中的任务。
     * <p>
     * 从队列头部取出任务，获取组合 permit 后执行原版操作。
     * permit 不足时停止处理，等待下一个 permit 释放时再次触发。
     * <p>
     * P0-4 修复：admissionPaused 时直接返回，普通任务不被启动。
     * 依赖关键任务（当前恒为 false）理论上可旁路，防止依赖饥饿。
     */
    private void drainPending() {
        while (true) {
            // P0-4 修复：紧急暂停时禁止启动任何普通任务
            if (admissionPaused) {
                return;
            }
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
            // 从队列移除（peek + poll 避免 permit 浪费）
            if (pendingNoiseTasks.poll() != task) {
                // 被其他线程取走了，释放 permit 重试
                stage.close();
                global.close();
                continue;
            }
            pendingCount.decrementAndGet();
            // 执行原版操作，完成后唤醒队列
            executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy(), global, stage);
        }
    }

    /**
     * 禁用调度器时强制唤醒所有等待任务（走原版路径）。
     */
    private void drainPendingForce() {
        PendingNoiseTask task;
        while ((task = pendingNoiseTasks.poll()) != null) {
            pendingCount.decrementAndGet();
            final PendingNoiseTask finalTask = task;
            // 直接执行原版操作，不获取 permit
            inflightCount.incrementAndGet();
            CompletableFuture<ChunkResult<ChunkAccess>> future;
            try {
                future = finalTask.operation().get();
            } catch (Throwable ex) {
                inflightCount.decrementAndGet();
                finalTask.proxy().completeExceptionally(ex);
                continue;
            }
            future.whenComplete((result, ex) -> {
                inflightCount.decrementAndGet();
                if (ex != null) {
                    finalTask.proxy().completeExceptionally(ex);
                } else {
                    finalTask.proxy().complete(result);
                }
            });
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
     * resumeExecutor 语义（审查建议）：permit 可用后通过该执行器恢复原版操作。
     * 当前实现使用 {@link Runnable#run()}（直接执行），因为 applyStep 是纯调度函数、
     * 不修改任何共享状态，在触发 drainPending 的线程执行是安全的。
     * 未来接入真正的 worldgen worker executor 后替换此字段即可。
     */
    private record PendingNoiseTask(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> operation,
            CompletableFuture<ChunkResult<ChunkAccess>> proxy,
            boolean isDependencyUnlock
    ) {
        /** 恢复执行器：当前直接执行（Runnable::run），预留原执行上下文接入点 */
        java.util.concurrent.Executor resumeExecutor() {
            return Runnable::run;
        }
    }
}
