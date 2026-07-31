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
     * 准入控制：Mixin 拦截 GenerationChunkHolder.applyStep 后调用此方法。
     * <p>
     * 流程（审查建议的最小接入路径）：
     * <ol>
     *   <li>调度器未启用 → 直接走原版路径</li>
     *   <li>非 NOISE 阶段 → 直接走原版路径（PR1 仅门控 NOISE）</li>
     *   <li>尝试获取 permit → 成功则执行原版操作，完成后释放 permit</li>
     *   <li>permit 不足 → 创建代理 Future，任务进入等待队列</li>
     * </ol>
     *
     * @param targetStatus     目标 ChunkStatus
     * @param isDependencyUnlock 是否为依赖解锁任务（可使用保留 permit）
     * @param originalOperation 原版 applyStep 操作（返回原版 Future）
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
            CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
            pendingNoiseTasks.offer(new PendingNoiseTask(originalOperation, proxy, isDependencyUnlock));
            pendingCount.incrementAndGet();
            return proxy;
        }

        // 尝试获取 permit
        if (!stageLimiter.tryAcquire(targetStatus, isDependencyUnlock)) {
            // permit 不足：创建代理 Future，放入等待队列
            CompletableFuture<ChunkResult<ChunkAccess>> proxy = new CompletableFuture<>();
            pendingNoiseTasks.offer(new PendingNoiseTask(originalOperation, proxy, isDependencyUnlock));
            pendingCount.incrementAndGet();
            return proxy;
        }

        // permit 获取成功：执行原版操作，完成后释放 permit 并唤醒等待队列
        return executeOriginal(targetStatus, originalOperation, null);
    }

    /**
     * 执行原版操作，完成后释放 permit 并唤醒等待队列。
     *
     * @param proxy 代理 Future（null 表示直接返回原版 Future）
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> executeOriginal(
            ChunkStatus targetStatus,
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> originalOperation,
            CompletableFuture<ChunkResult<ChunkAccess>> proxy) {

        inflightCount.incrementAndGet();
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        try {
            future = originalOperation.get();
        } catch (Throwable ex) {
            // 原版操作抛异常：释放 permit 并传播异常
            stageLimiter.release(targetStatus);
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
            stageLimiter.release(targetStatus);
            inflightCount.decrementAndGet();
            if (proxy != null) {
                // 完成代理 Future
                if (ex != null) {
                    proxy.completeExceptionally(ex);
                } else {
                    proxy.complete(result);
                }
            }
            // 尝试唤醒等待队列
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
     * 从队列头部取出任务，获取 permit 后执行原版操作。
     * permit 不足时停止处理，等待下一个 permit 释放时再次触发。
     */
    private void drainPending() {
        while (true) {
            PendingNoiseTask task = pendingNoiseTasks.peek();
            if (task == null) {
                return;
            }
            // 尝试获取 permit
            if (!stageLimiter.tryAcquire(ChunkStatus.NOISE, task.isDependencyUnlock())) {
                return; // permit 不足，等待下次触发
            }
            // 从队列移除（peek + poll 避免 permit 浪费）
            if (pendingNoiseTasks.poll() != task) {
                // 被其他线程取走了，释放 permit 重试
                stageLimiter.release(ChunkStatus.NOISE);
                continue;
            }
            pendingCount.decrementAndGet();
            // 执行原版操作，完成后唤醒队列
            executeOriginal(ChunkStatus.NOISE, task.operation(), task.proxy());
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
     * §9.4 服务器关闭时调用：清空所有队列。
     */
    public void clearAll() {
        pendingNoiseTasks.clear();
        pendingCount.set(0);
        inflightCount.set(0);
        watchdog.clear();
        SteadyChunks.LOGGER.info("SteadyChunks 调度器已清空所有队列");
    }

    /**
     * 等待中的 NOISE 任务。
     */
    private record PendingNoiseTask(
            Supplier<CompletableFuture<ChunkResult<ChunkAccess>>> operation,
            CompletableFuture<ChunkResult<ChunkAccess>> proxy,
            boolean isDependencyUnlock
    ) {}
}
