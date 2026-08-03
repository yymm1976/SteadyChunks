package com.mochi_753.steadychunks.io;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.features.CrossChunkAccessCache;
import com.mochi_753.steadychunks.light.LightSendCoordinator;
import com.mochi_753.steadychunks.light.LightTaskBudget;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.structure.DatapackGenerationRegistry;
import com.mochi_753.steadychunks.structure.PlacementCandidateCache;
import com.mochi_753.steadychunks.structure.StructureStartIndex;
import com.mochi_753.steadychunks.structure.TemplateMetadataCache;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生命周期清理协调器，对应开发计划 §9.4。
 * <p>
 * 协调区块卸载、维度卸载、服务器关闭时的清理工作：
 * <ul>
 *   <li>区块卸载时释放 SteadyChunks 的所有缓存和任务引用</li>
 *   <li>维度卸载时取消等待任务</li>
 *   <li>服务器关闭时拒绝新任务并安全排空</li>
 *   <li>提供泄漏检测计数</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §9 风险表）：
 * <ul>
 *   <li>写入重排破坏 RegionFile 语义 → 同区域串行化（由 IoQueueController 处理）</li>
 *   <li>停服排空时间过长 → 停服模式提升写入预算并停止新生成</li>
 *   <li>保存饥饿 → 老化优先级和硬性最大等待时间</li>
 * </ul>
 * <p>
 * 本类作为各模块生命周期的统一协调点，避免各模块自行监听事件导致遗漏。
 */
public final class LifecycleCleanupCoordinator {
    private static LifecycleCleanupCoordinator instance;

    /** 按玩家索引的活跃引用（泄漏检测用） */
    private final ConcurrentHashMap<UUID, AtomicInteger> playerReferences = new ConcurrentHashMap<>();
    /** 累计卸载区块数 */
    private final AtomicLong totalChunksUnloaded = new AtomicLong(0);
    /** 累计卸载维度数 */
    private final AtomicLong totalDimensionsUnloaded = new AtomicLong(0);
    /** 累计检测到的泄漏（卸载后仍有引用） */
    private final AtomicLong totalLeaksDetected = new AtomicLong(0);
    /** 停服模式标志 */
    private final AtomicBoolean shutdownMode = new AtomicBoolean(false);

    /**
     * 第 11 轮 P0/P1 修复：服务器生命周期计数（generation-local counter）。
     * <p>
     * registration 捕获注册时的 {@link ServerLifecycle} 对象，close 只递减捕获对象——
     * 旧服务器生命周期的迟到 lease 不会污染新服务器（旧实现共享 globalTaskCount，
     * 旧任务在 ServerStopped 后迟到 close 会把新生命周期计数抹掉甚至减成负数）。
     * 新服务器启动时原子替换 {@link #currentLifecycle}，旧对象随旧任务自然归零。
     * <p>
     * 第 12 轮 P1 修复：维度计数随生命周期隔离——旧服务器迟到任务与新服务器同
     * 维度任务不再共享同一 counter（旧实现维度 Map 在协调器上全局共享，重叠窗口
     * 内 detectLeaks 比较维度总和与全局计数会产生假泄漏）。
     */
    static final class ServerLifecycle {
        final long generation;
        final AtomicInteger activeTasks = new AtomicInteger(0);
        final AtomicBoolean accepting = new AtomicBoolean(true);
        /** 按维度索引的活跃任务计数（随生命周期隔离，泄漏检测用） */
        final ConcurrentHashMap<ResourceKey<Level>, AtomicInteger> dimensionTaskCounts = new ConcurrentHashMap<>();

        ServerLifecycle(long generation) {
            this.generation = generation;
        }
    }

    /** 当前服务器生命周期（onServerStart 替换、onServerShutdown 关闭 accepting） */
    private final AtomicReference<ServerLifecycle> currentLifecycle =
            new AtomicReference<>(new ServerLifecycle(0));
    /** 服务器生命周期代数计数器（新 lifecycle 的 generation 来源） */
    private final AtomicLong serverGeneration = new AtomicLong(0);

    /** 是否接受新注册（供 controlAdmission 停服门前置，P1） */
    public boolean isAcceptingRegistrations() {
        return !shutdownMode.get() && currentLifecycle.get().accepting.get();
    }

    private LifecycleCleanupCoordinator() {
    }

    public static synchronized LifecycleCleanupCoordinator getInstance() {
        if (instance == null) {
            instance = new LifecycleCleanupCoordinator();
        }
        return instance;
    }

    /**
     * 任务注册 lease（第 8 轮 P0 修复）：从任务创建持有到代理 Future 终态。
     * <p>
     * 替换旧 registerTask/unregisterTask 的两个发布竞态：
     * <ol>
     *   <li>旧实现先入队后注册，drainer 可能在注册前 poll 并注销，计数短暂为负；
     *       lease 先注册、再发布，poll 时计数必然已计入。</li>
     *   <li>旧实现停服模式下注册失败（返回 false）后任务仍入队，出队时无条件注销，
     *       计数永久变负；lease 失败时调用方直接拒绝任务，不入队。</li>
     * </ol>
     * 计数语义（第 8 轮方案 B）：统计完整任务生命周期——等待队列、已出队未提交、
     * 已提交未运行、运行中均计入，直到代理 Future 进入终态才 close。
     * 因此停服等待 {@link #globalTaskCount()} 归零 = 真正等待所有活跃任务完成，
     * 维度卸载泄漏检测也覆盖运行中任务。
     */
    public interface TaskRegistration extends AutoCloseable {
        /** 注册是否生效（停服模式下为 false，调用方必须拒绝任务）。 */
        boolean registered();

        /** 幂等注销：仅首次调用递减计数。 */
        @Override
        void close();
    }

    /** 停服模式下的空 lease：未注册，close 无操作。 */
    private static final TaskRegistration UNREGISTERED = new TaskRegistration() {
        @Override
        public boolean registered() {
            return false;
        }

        @Override
        public void close() {
        }
    };

    /**
     * 注册新任务（先注册、再发布到队列）。
     * <p>
     * 第 9 轮 P0-2 修复：lease 捕获注册时的实际维度 counter 对象，close 时通过
     * {@code remove(dimension, counter)} 只删除自己对应的 counter。旧实现按维度 key
     * 重新 computeIfPresent 递减：维度卸载 remove entry 后重载产生的新 counter 会被
     * 旧 generation 的迟到 lease 误减（串代污染）。
     *
     * @return 注册 lease；{@code registered()==false} 表示停服模式，调用方必须拒绝任务
     */
    public TaskRegistration tryRegisterTask(ResourceKey<Level> dimension) {
        if (shutdownMode.get()) {
            return UNREGISTERED; // 停服模式拒绝新任务
        }
        // 第 11 轮 P0/P1 修复：捕获当前服务器生命周期对象（注册后二次校验 + close
        // 只递减捕获对象，旧代迟到 close 不污染新服务器计数）。
        ServerLifecycle lifecycle = currentLifecycle.get();
        if (!lifecycle.accepting.get()) {
            return UNREGISTERED;
        }
        lifecycle.activeTasks.incrementAndGet();
        // 第 12 轮 P1 修复：counter 从捕获的生命周期内取——旧代迟到 close 只影响
        // 旧 lifecycle 的维度 Map，新服务器同名维度计数不受污染。
        AtomicInteger dimensionCounter =
                lifecycle.dimensionTaskCounts.computeIfAbsent(dimension, k -> new AtomicInteger(0));
        dimensionCounter.incrementAndGet();
        // 注册后二次校验：停服在"检查门与递增"之间越过时回退计数，不返回有效 lease。
        if (!lifecycle.accepting.get() || lifecycle != currentLifecycle.get()) {
            lifecycle.activeTasks.decrementAndGet();
            int remaining = dimensionCounter.decrementAndGet();
            if (remaining == 0) {
                lifecycle.dimensionTaskCounts.remove(dimension, dimensionCounter);
            }
            return UNREGISTERED;
        }
        return new TaskRegistration() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public boolean registered() {
                return true;
            }

            @Override
            public void close() {
                // 幂等：仅首次 close 递减。递减捕获的服务器生命周期（旧代 lease 迟到
                // close 只影响旧 counter 与旧维度 Map，不污染新服务器）与捕获的维度
                // counter（remove(key, value) 防串代删除，与维度卸载 remove 并发为无操作）。
                if (closed.compareAndSet(false, true)) {
                    lifecycle.activeTasks.decrementAndGet();
                    int remaining = dimensionCounter.decrementAndGet();
                    if (remaining == 0) {
                        lifecycle.dimensionTaskCounts.remove(dimension, dimensionCounter);
                    }
                }
            }
        };
    }

    /**
     * 注册玩家引用（玩家请求区块时调用）。
     */
    public void registerPlayerReference(UUID playerId) {
        playerReferences.computeIfAbsent(playerId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 注销玩家引用。
     */
    public void unregisterPlayerReference(UUID playerId) {
        AtomicInteger count = playerReferences.get(playerId);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    /**
     * 区块卸载时调用：释放该区块相关的所有缓存和任务引用。
     * <p>
     * 清理范围：
     * <ul>
     *   <li>PlacementCandidateCache 中的选址缓存</li>
     *   <li>StructureStartIndex 中的结构起点（如属于此区块）</li>
     *   <li>CrossChunkAccessCache 中的引用（ThreadLocal 自动清理）</li>
     *   <li>ChunkScheduler 中的任务</li>
     * </ul>
     *
     * @param dimension 目标维度
     * @param packedChunkPos 卸载的区块 packed long
     */
    public void onChunkUnload(ResourceKey<Level> dimension, long packedChunkPos) {
        totalChunksUnloaded.incrementAndGet();

        // 审查修复：ChunkScheduler 不再管理区块级任务（删除了 ChunkTaskGraph）
        // permit 在 Future 完成时自动释放，无需区块级清理

        SteadyChunks.LOGGER.debug("SteadyChunks 清理区块: dim={} pos={}", dimension.location(), packedChunkPos);
    }

    /**
     * 维度卸载时调用：取消该维度的所有等待任务，清理缓存。
     * <p>
     * 清理范围：
     * <ul>
     *   <li>取消该维度的所有等待任务</li>
     *   <li>§17.2 通过 DatapackGenerationRegistry 统一通知注册缓存失效</li>
     *   <li>清理 LightTaskBudget 该维度计数</li>
     *   <li>清理 CrossChunkAccessCache（ThreadLocal 局部缓存）</li>
     * </ul>
     *
     * @param dimension 卸载的维度
     * @param dimensionId 维度 numeric ID（保留兼容，Registry 统一通知后部分缓存不再使用此参数）
     */
    public void onDimensionUnload(ResourceKey<Level> dimension, int dimensionId) {
        totalDimensionsUnloaded.incrementAndGet();

        // P1-1 修复（第 6/7 轮）：维度卸载时定向取消该维度的等待任务。
        // 等待任务持有 GeneratingChunkMap / GenerationChunkHolder / 原操作 / 代理 Future，
        // 若维度已卸载仍留在队列会持续持有已卸载维度的生成上下文；且 mailbox 恢复可能
        // 发生在维度卸载之后。第 7 轮起 cancelDimension 同时关闭该维度生命周期，
        // 已出队/已提交但未运行的任务在运行前被拒绝。
        ChunkScheduler.getInstance().cancelDimension(dimension, "Dimension unloaded");

        // §17.2 统一通知注册缓存失效（PlacementCandidateCache / TemplateMetadataCache / StructureStartIndex）
        DatapackGenerationRegistry.getInstance().fireDimensionUnload(dimension);

        // 清理未注册到 Registry 的模块
        CrossChunkAccessCache.current().clear();
        LightTaskBudget.getInstance().clearDimension(dimension);

        // P1 修复（第 7 轮）：泄漏检测移到清理后读取。
        // 旧实现在清理前读计数：维度卸载开始时待办任务仍非零（尚未取消），
        // 即便清理全部成功也会被误报为泄漏。清理后读取才反映真实残留。
        // 第 8 轮方案 B 语义：维度计数覆盖完整任务生命周期（等待/已出队/已提交/运行中）。
        // cancelDimension 同步取消队列任务并 close 其 lease；已出队/运行中任务
        // 在代理 Future 终态才 close。因此清理后残留 = 正在收尾的任务（正常，最终归零）
        // 或永不终结的任务（真泄漏，如原版生成 Future 随维度卸载被丢弃）。
        // 第 12 轮 P1 修复：读当前生命周期的维度 Map——旧生命周期迟到任务不混入。
        ServerLifecycle lifecycle = currentLifecycle.get();
        int remainingTasks = lifecycle.dimensionTaskCounts.getOrDefault(dimension, new AtomicInteger(0)).get();
        if (remainingTasks > 0) {
            totalLeaksDetected.addAndGet(remainingTasks);
            SteadyChunks.LOGGER.warn("SteadyChunks 维度卸载泄漏: dim={} 残留任务={}", dimension.location(), remainingTasks);
        }

        // 移除当前生命周期维度计数 entry；此后该维度任务 close 时 remove(key, value)
        // 为无操作（不会重建 0 → -1 的负数 entry，修复旧 unregisterTask 的永久负计数
        // 污染）；旧生命周期迟到 close 只影响旧 Map，无副作用。
        lifecycle.dimensionTaskCounts.remove(dimension);
        SteadyChunks.LOGGER.info("SteadyChunks 维度卸载完成: {}", dimension.location());
    }

    /**
     * 玩家断开时调用：清理该玩家相关的引用。
     *
     * @param playerId 玩家 ID
     */
    public void onPlayerDisconnect(UUID playerId) {
        int remaining = playerReferences.getOrDefault(playerId, new AtomicInteger(0)).get();
        if (remaining > 0) {
            totalLeaksDetected.addAndGet(remaining);
            SteadyChunks.LOGGER.warn("SteadyChunks 玩家断开泄漏: player={} 残留引用={}", playerId, remaining);
        }

        // 清理各模块该玩家状态
        LightSendCoordinator.getInstance().clearPlayer(playerId);
        ChunkSendQuota.getInstance().clearPlayer(playerId);

        playerReferences.remove(playerId);
    }

    /**
     * 服务器（重新）启动时调用：恢复接收新任务。
     * <p>
     * 第 9 轮 P1 修复：集成服务器玩家退出世界回到主菜单后，同一 JVM 可能再开新世界——
     * 停服时 shutdownMode 置位且无恢复入口，静态单例会永久拒绝后续世界的任务注册。
     * 本方法配合 ServerStartingEvent 恢复：
     * <ul>
     *   <li>关闭停服模式（恢复接受新任务注册）</li>
     *   <li>调度器 resetForReload（恢复全局 accepting，清空可能残留的队列）</li>
     *   <li>I/O 队列退出停服模式</li>
     * </ul>
     * 计数不清零：旧服务器生命周期的迟到 lease 会在任务终态自行递减到零
     * （forceClearAll 已不再强制归零，见 {@link #forceClearAll()}）。
     */
    public void onServerStart() {
        SteadyChunks.LOGGER.info("SteadyChunks 服务器启动：恢复任务接收");
        shutdownMode.set(false);
        // 第 11 轮 P0/P1 修复：原子替换为新的服务器生命周期（generation-local counter）——
        // 旧生命周期的迟到 lease 只递减旧 counter，不污染新服务器（旧实现共享计数，
        // 旧任务在 ServerStopped 后迟到 close 会把新计数抹掉甚至减成负数）。
        currentLifecycle.set(new ServerLifecycle(serverGeneration.incrementAndGet()));
        // 第 9 轮 P1 修复：启动场景不清空队列（resetForReload 会以 error result 清空
        // spawn 区域真实生成残留任务，导致区块卡在 NOISE 之前、后续强制同步加载死锁）。
        // 队列残留（若有）由维度卸载清理（LevelEvent.Unload → cancelDimension）兜底，
        // 启动时自然排空即可。
        ChunkScheduler.getInstance().resumeAccepting();
        IoQueueController.getInstance().exitShutdownMode();
    }

    /**
     * 服务器关闭时调用（ServerStoppingEvent）：拒绝新任务并立即清空等待队列。
     * <p>
     * 第 10 轮 P0-3 修复：不再在 Server Thread 上 sleep 等待任务归零——
     * <ul>
     *   <li>旧实现在等待循环里 sleep，而 pending 任务需要 scheduler tick / mailbox
     *       恢复推进，Server Thread 睡眠时两者都不动 → 必然等到超时（5 秒）；</li>
     *   <li>新顺序：先关注册门（P0-2）→ 立即 closeForShutdown 以 error result 完成
     *       所有 pending → 已运行任务由原 Future 自然终结（迟到 lease 归零）；</li>
     *   <li>最终缓存清理与泄漏报告移到 {@link #onServerStopped()}（ServerStoppedEvent），
     *       此时运行中任务应已结束。</li>
     * </ul>
     */
    public void onServerShutdown() {
        SteadyChunks.LOGGER.info("SteadyChunks 开始停服清理");
        // 1. 先关闭当前生命周期的注册门（P0-2：check-then-act 窗口收窄——
        //    此后新注册二次校验失败回退）
        shutdownMode.set(true);
        currentLifecycle.get().accepting.set(false);

        // 2. I/O 队列进入停服模式（提升写入预算）
        IoQueueController.getInstance().enterShutdownMode();

        // 3. 立即以 error result 完成所有 pending（不再等待——等待会阻塞 Server
        //    Thread 导致 pending 无法推进，见 javadoc）
        ChunkScheduler.getInstance().closeForShutdown(new IllegalStateException("Server stopping"));

        int remaining = currentLifecycle.get().activeTasks.get();
        if (remaining > 0) {
            SteadyChunks.LOGGER.info("SteadyChunks 停服：等待 {} 个运行中任务自然结束（迟到 lease 自行归零）",
                    remaining);
        }
    }

    /**
     * 服务器已停止时调用（ServerStoppedEvent）：最终清理缓存与泄漏报告。
     * <p>
     * 第 10 轮 P0-3 修复：从 {@link #onServerShutdown()} 拆出的后半段——此时
     * Server Thread 已停止，运行中任务应已终结（迟到 lease 递减计数），残留计数
     * 即真泄漏。
     * <p>
     * 第 11 轮修复：不再清零计数（旧实现 set(0)+clear 会被旧生命周期迟到 close
     * 污染成负数，且把残留带进新生命周期）——ServerLifecycle 对象隔离：新服务器
     * 由 onServerStart 原子替换，旧 lease 只递减旧 counter，泄漏报告读当前对象即可。
     */
    public void onServerStopped() {
        forceClearAll();
        int remaining = currentLifecycle.get().activeTasks.get();
        if (remaining > 0) {
            totalLeaksDetected.addAndGet(remaining);
            SteadyChunks.LOGGER.warn("SteadyChunks 停服完成残留任务: {}（记为泄漏）", remaining);
        }
        SteadyChunks.LOGGER.info("SteadyChunks 停服清理完成: 卸载区块={} 卸载维度={} 检测泄漏={}",
                totalChunksUnloaded.get(), totalDimensionsUnloaded.get(), totalLeaksDetected.get());
    }

    /**
     * 强制清理所有模块的状态（停服最后阶段）。
     */
    private void forceClearAll() {
        // 审查修复：调度器等待任务必须异常完成代理 Future，避免区块永久等待
        // P1 修复（第 5 轮）：服务器永久关闭使用 closeForShutdown（清理后不再接收新任务）
        ChunkScheduler.getInstance().closeForShutdown(new IllegalStateException("Server stopping"));
        FullCommitQueue.getInstance().clear();
        IoQueueController.getInstance().clearAll();
        LightSendCoordinator.getInstance().clearAll();
        LightTaskBudget.getInstance().clearAll();
        PlacementCandidateCache.getInstance().clear();
        StructureStartIndex.getInstance().clear();
        TemplateMetadataCache.getInstance().clear();
        CrossChunkAccessCache.current().clear();
        playerReferences.clear();
    }

    /**
     * 执行泄漏检测（定期调用，如每 6000 tick）。
     * <p>
     * 检查各模块的活跃计数是否与全局计数一致。
     *
     * @return 检测到的泄漏总数（0 表示无泄漏）
     */
    public int detectLeaks() {
        int leaks = 0;
        int global = currentLifecycle.get().activeTasks.get();
        int sum = 0;
        // 第 12 轮 P1 修复：维度总和取自当前生命周期——全局与维度计数天然同代，
        // 旧生命周期迟到任务不会造成假泄漏。
        for (AtomicInteger c : currentLifecycle.get().dimensionTaskCounts.values()) {
            sum += c.get();
        }
        if (sum != global) {
            leaks += Math.abs(global - sum);
            SteadyChunks.LOGGER.warn("SteadyChunks 泄漏检测: 全局任务计数 {} != 维度计数总和 {}", global, sum);
        }
        return leaks;
    }

    // 诊断访问器
    /** 当前服务器生命周期的活跃任务计数（旧生命周期残留不纳入） */
    public int globalTaskCount() { return currentLifecycle.get().activeTasks.get(); }
    public int dimensionTaskCount(ResourceKey<Level> dim) {
        // 第 12 轮 P1 修复：读当前生命周期的维度 Map（旧代迟到任务不混入）
        return currentLifecycle.get().dimensionTaskCounts.getOrDefault(dim, new AtomicInteger(0)).get();
    }
    public long totalChunksUnloaded() { return totalChunksUnloaded.get(); }
    public long totalDimensionsUnloaded() { return totalDimensionsUnloaded.get(); }
    public long totalLeaksDetected() { return totalLeaksDetected.get(); }
    public boolean isShutdownMode() { return shutdownMode.get(); }
}
