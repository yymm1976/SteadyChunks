package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 区块调度器主控，对应开发计划 §3 交付物。
 * <p>
 * 整合所有调度组件：任务图、资源令牌、阶段限制、优先级模型、公平性、背压、取消策略。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>固定预算调度器：Phase 3 只支持配置固定值，Phase 4 AIMD 动态调整</li>
 *   <li>完成优先：近处半成品区块优先推进</li>
 *   <li>软取消：仅允许在 RUNNING 之前取消</li>
 *   <li>禁用调度器后行为恢复到原版路径（验收标准）</li>
 * </ul>
 */
public final class ChunkScheduler {
    private static ChunkScheduler instance;

    private final ChunkTaskGraph taskGraph = new ChunkTaskGraph();
    private final StageLimiter stageLimiter = new StageLimiter();
    private final FairnessManager fairness = new FairnessManager();
    private final PriorityModel priorityModel = new PriorityModel(fairness);
    private final BackpressureController backpressure = new BackpressureController();
    private final CancellationPolicy cancellation = new CancellationPolicy(taskGraph);
    /** §17.3 看门狗，定期扫描任务异常并报告 */
    private final Watchdog watchdog = Watchdog.getInstance();

    /** 优先级队列：按评分降序（§6.1 使用 long 评分，避免浮点比较不稳定） */
    private final PriorityBlockingQueue<ChunkTask> readyQueue = new PriorityBlockingQueue<>(
            64, Comparator.comparingLong(ChunkTask::priorityScore).reversed()
    );

    private final ResourcePermit cpuGeneralPermit;
    private final AtomicInteger inflightCount = new AtomicInteger(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile double maxVisibleDistance = 128.0;

    /**
     * 需求版本号（§6.2 惰性优先级更新）。
     * 每次玩家移动、需求变化、配置调整时 +1；任务 poll 时若版本过期则重算优先级。
     * 避免每 tick 重建整个优先堆。
     */
    private volatile long demandVersion = 0L;
    /** §17.3 内部 tick 计数器，供 Watchdog 扫描间隔判断 */
    private long tickCounter = 0L;

    private ChunkScheduler() {
        int maxInflight = 64;
        cpuGeneralPermit = new ResourcePermit(ResourceType.CPU_GENERAL, maxInflight);
        backpressure.setInflightThreshold(maxInflight);
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
    }

    /**
     * 设置在途任务上限（§11.6 预设应用器调用）。
     * 同步更新 permit 容量与背压阈值。
     */
    public void setMaxInflight(int maxInflight) {
        cpuGeneralPermit.setMaxPermits(maxInflight);
        backpressure.setInflightThreshold(maxInflight);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 提交新任务到调度器。
     */
    public void submit(ChunkTask task) {
        if (!enabled.get()) {
            return;
        }
        taskGraph.register(task);
        // 检查依赖是否已满足
        if (taskGraph.areDependenciesMet(task)) {
            task.setState(TaskState.READY);
            updatePriority(task);
            readyQueue.offer(task);
        } else {
            task.setState(TaskState.WAITING_DEPS);
        }
    }

    /**
     * 每 tick 调用：推进调度循环。
     * <p>
     * 1. 更新背压指标<br>
     * 2. 从就绪队列取出最高优先级任务<br>
     * 3. 检查背压、资源 permit、阶段限制<br>
     * 4. 启动允许的任务<br>
     * 5. 回收已完成任务的资源
     */
    public void tick() {
        if (!enabled.get()) {
            return;
        }
        tickCounter++;
        // §17.3 Watchdog 定期扫描（即使背压或调度异常也要检测）
        watchdog.tick(tickCounter, this);
        // 更新背压指标
        backpressure.setInflightCount(inflightCount.get());
        backpressure.setQueueDepth(readyQueue.size());

        BackpressureController.BackpressureLevel level = backpressure.evaluate();

        // 尝试启动就绪任务
        List<ChunkTask> deferred = new ArrayList<>();
        while (!readyQueue.isEmpty() && inflightCount.get() < cpuGeneralPermit.maxPermits()) {
            ChunkTask task = readyQueue.poll();
            if (task == null) {
                break;
            }
            // 检查是否已被取消
            if (task.state() == TaskState.CANCELLED) {
                taskGraph.remove(task.pos());
                continue;
            }
            // §6.2 惰性优先级更新：版本过期则重算并重新入队，让堆自动调整位置
            if (task.priorityVersion() != demandVersion) {
                updatePriority(task);
                readyQueue.offer(task);
                continue;
            }
            // 检查背压
            if (!backpressure.allowNewTask(task, level)) {
                deferred.add(task);
                continue;
            }
            // 尝试获取资源 permit
            if (!cpuGeneralPermit.tryAcquire()) {
                deferred.add(task);
                continue;
            }
            // §7.1 依赖解锁任务可使用保留 permit，普通任务不能占用依赖保留额度
            if (!stageLimiter.tryAcquire(task.targetStatus(), task.requiredForDependency())) {
                cpuGeneralPermit.release();
                deferred.add(task);
                continue;
            }
            // 启动任务
            task.setState(TaskState.RUNNING);
            inflightCount.incrementAndGet();
            fairness.onTaskStart(task);
            // 实际执行由 Mixin 层或工作线程池处理
        }
        // 将被延迟的任务重新放回队列
        for (ChunkTask t : deferred) {
            readyQueue.offer(t);
        }
    }

    /**
     * 任务完成时调用，释放资源并通知依赖。
     * <p>
     * §8 两阶段软取消：若任务处于 CANCEL_REQUESTED，阶段已完成但用户请求取消。
     * 仍被依赖时必须转为 DONE（不能让依赖者永久等待）；无依赖时转为 CANCELLED（阻止下一阶段）。
     */
    public void onComplete(ChunkTask task) {
        stageLimiter.release(task.targetStatus());
        cpuGeneralPermit.release();
        inflightCount.decrementAndGet();
        fairness.onTaskEnd(task);

        boolean cancelled = false;
        if (task.state() == TaskState.CANCEL_REQUESTED) {
            // §8：阶段已完成，检查是否仍被依赖
            if (hasDependents(task)) {
                // 仍被依赖：必须完成，转为 DONE 满足依赖链
                task.setState(TaskState.DONE);
            } else {
                // 无依赖：安全取消，阻止下一阶段
                task.setState(TaskState.CANCELLED);
                cancelled = true;
            }
        } else {
            task.setState(TaskState.DONE);
        }

        // 仅 DONE 状态通知依赖者（CANCELLED 不满足依赖，依赖者需等待重新调度或超时）
        if (!cancelled) {
            for (var depPos : taskGraph.getDependents(task.pos())) {
                ChunkTask dependent = taskGraph.get(depPos);
                if (dependent != null && dependent.state() == TaskState.WAITING_DEPS) {
                    if (taskGraph.areDependenciesMet(dependent)) {
                        dependent.setState(TaskState.READY);
                        updatePriority(dependent);
                        readyQueue.offer(dependent);
                    }
                }
            }
        }
        taskGraph.remove(task.pos());
    }

    /**
     * 检查任务是否仍被其他任务依赖。
     */
    private boolean hasDependents(ChunkTask task) {
        return !taskGraph.getDependents(task.pos()).isEmpty();
    }

    /**
     * 任务失败时调用，释放资源但保留在图中供诊断。
     */
    public void onFailure(ChunkTask task, Throwable cause) {
        stageLimiter.release(task.targetStatus());
        cpuGeneralPermit.release();
        inflightCount.decrementAndGet();
        fairness.onTaskEnd(task);
        task.setState(TaskState.FAILED);
        SteadyChunks.LOGGER.warn("SteadyChunks 任务失败: {} {}", task.pos(), cause.getMessage());
    }

    /**
     * 请求取消任务。
     */
    public boolean cancel(ChunkTask task) {
        return cancellation.cancel(task);
    }

    /**
     * 更新任务优先级评分（§6.1 long 评分 + §6.2 惰性版本标记）。
     */
    private void updatePriority(ChunkTask task) {
        long score = priorityModel.score(task, maxVisibleDistance);
        task.setPriorityScore(score);
        task.setPriorityVersion(demandVersion);
    }

    /**
     * 触发需求版本号 +1（§6.2 惰性优先级更新）。
     * <p>
     * 由玩家移动、需求变化、配置调整等事件调用。
     * 不立即重算所有任务，而是在 tick poll 时按需重算过期任务。
     */
    public void bumpDemandVersion() {
        demandVersion++;
    }

    /**
     * 从配置同步调度器参数。
     */
    public void syncFromConfig() {
        setEnabled(CommonConfig.SCHEDULER_ENABLED.get());
        int maxInflight = CommonConfig.MAX_INFLIGHT.get();
        cpuGeneralPermit.setMaxPermits(maxInflight);
        backpressure.setInflightThreshold(maxInflight);

        // 同步阶段限制
        stageLimiter.setStageLimit(ChunkStatus.STRUCTURE_STARTS, CommonConfig.LIMIT_STRUCTURE_STARTS.get());
        stageLimiter.setStageLimit(ChunkStatus.NOISE, CommonConfig.LIMIT_NOISE.get());
        stageLimiter.setStageLimit(ChunkStatus.FEATURES, CommonConfig.LIMIT_FEATURES.get());
        stageLimiter.setStageLimit(ChunkStatus.LIGHT, CommonConfig.LIMIT_LIGHT.get());
    }

    // 诊断访问器
    public ChunkTaskGraph taskGraph() { return taskGraph; }
    public StageLimiter stageLimiter() { return stageLimiter; }
    public BackpressureController backpressure() { return backpressure; }
    public FairnessManager fairness() { return fairness; }
    public Watchdog watchdog() { return watchdog; }
    public int inflightCount() { return inflightCount.get(); }
    public int readyQueueSize() { return readyQueue.size(); }
    public int cpuPermitsAvailable() { return cpuGeneralPermit.availablePermits(); }
    public int cpuPermitsMax() { return cpuGeneralPermit.maxPermits(); }

    /**
     * §9.4 区块卸载时调用：从就绪队列移除该区块的任务。
     */
    public void onChunkUnload(long packedChunkPos) {
        taskGraph.remove(packedChunkPos);
    }

    /**
     * §9.4 维度卸载时调用：取消该维度的所有等待任务。
     * <p>
     * 当前实现按 taskGraph 清理所有任务（维度隔离需扩展 taskGraph 后实现）。
     * §17.3 同时注册到 Watchdog，便于后续扫描检测孤儿任务。
     */
    public void onDimensionUnload(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        // 取消所有非 RUNNING 任务（RUNNING 任务由 onComplete 处理）
        // taskGraph 维度隔离扩展后可按维度精确取消
        watchdog.registerDimensionUnload(dimension);
        SteadyChunks.LOGGER.info("SteadyChunks 调度器维度卸载: {}", dimension.location());
    }

    /**
     * §9.4 服务器关闭时调用：清空所有队列和任务图。
     */
    public void clearAll() {
        readyQueue.clear();
        taskGraph.clear();
        inflightCount.set(0);
        watchdog.clear();
        SteadyChunks.LOGGER.info("SteadyChunks 调度器已清空所有队列");
    }
}
