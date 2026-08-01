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

    /** 按维度索引的活跃任务计数（泄漏检测用） */
    private final ConcurrentHashMap<ResourceKey<Level>, AtomicInteger> dimensionTaskCounts = new ConcurrentHashMap<>();
    /** 按玩家索引的活跃引用（泄漏检测用） */
    private final ConcurrentHashMap<UUID, AtomicInteger> playerReferences = new ConcurrentHashMap<>();
    /** 全局活跃任务计数 */
    private final AtomicInteger globalTaskCount = new AtomicInteger(0);
    /** 累计卸载区块数 */
    private final AtomicLong totalChunksUnloaded = new AtomicLong(0);
    /** 累计卸载维度数 */
    private final AtomicLong totalDimensionsUnloaded = new AtomicLong(0);
    /** 累计检测到的泄漏（卸载后仍有引用） */
    private final AtomicLong totalLeaksDetected = new AtomicLong(0);
    /** 停服模式标志 */
    private final AtomicBoolean shutdownMode = new AtomicBoolean(false);

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
     *
     * @return 注册 lease；{@code registered()==false} 表示停服模式，调用方必须拒绝任务
     */
    public TaskRegistration tryRegisterTask(ResourceKey<Level> dimension) {
        if (shutdownMode.get()) {
            return UNREGISTERED; // 停服模式拒绝新任务
        }
        globalTaskCount.incrementAndGet();
        dimensionTaskCounts.computeIfAbsent(dimension, k -> new AtomicInteger(0)).incrementAndGet();
        return new TaskRegistration() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public boolean registered() {
                return true;
            }

            @Override
            public void close() {
                // 幂等：仅首次 close 递减。维度计数归零即移除 entry：
                // 与 onDimensionUnload 的 remove 并发时 computeIfPresent 为无操作，
                // 不会重建负数 entry（旧 unregisterTask 在 entry 被 remove 后
                // 会重建 0 → -1，永久污染计数）。
                if (closed.compareAndSet(false, true)) {
                    globalTaskCount.decrementAndGet();
                    dimensionTaskCounts.computeIfPresent(dimension, (ignored, count) -> {
                        int remaining = count.decrementAndGet();
                        return remaining == 0 ? null : count;
                    });
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
        int remainingTasks = dimensionTaskCounts.getOrDefault(dimension, new AtomicInteger(0)).get();
        if (remainingTasks > 0) {
            totalLeaksDetected.addAndGet(remainingTasks);
            SteadyChunks.LOGGER.warn("SteadyChunks 维度卸载泄漏: dim={} 残留任务={}", dimension.location(), remainingTasks);
        }

        // 移除维度计数 entry；此后该维度任务 close 时 computeIfPresent 为无操作
        // （不会重建 0 → -1 的负数 entry，修复旧 unregisterTask 的永久负计数污染）。
        dimensionTaskCounts.remove(dimension);
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
     * 服务器关闭时调用：拒绝新任务，安全排空。
     * <p>
     * 步骤：
     * <ol>
     *   <li>进入停服模式，拒绝新任务</li>
     *   <li>I/O 队列进入停服模式（提升写入预算）</li>
     *   <li>等待活跃任务完成（有限时间）</li>
     *   <li>强制清理所有缓存和队列</li>
     * </ol>
     *
     * @param maxWaitMs 最大等待时间（毫秒）
     */
    public void onServerShutdown(long maxWaitMs) {
        SteadyChunks.LOGGER.info("SteadyChunks 开始停服清理");
        shutdownMode.set(true);

        // 1. I/O 队列进入停服模式
        IoQueueController.getInstance().enterShutdownMode();

        // 2. 等待活跃任务完成
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (globalTaskCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int remaining = globalTaskCount.get();
        if (remaining > 0) {
            totalLeaksDetected.addAndGet(remaining);
            SteadyChunks.LOGGER.warn("SteadyChunks 停服超时残留任务: {}", remaining);
        }

        // 3. 强制清理所有缓存和队列
        forceClearAll();

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
        dimensionTaskCounts.clear();
        playerReferences.clear();
        globalTaskCount.set(0);
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
        int global = globalTaskCount.get();
        int sum = 0;
        for (AtomicInteger c : dimensionTaskCounts.values()) {
            sum += c.get();
        }
        if (sum != global) {
            leaks += Math.abs(global - sum);
            SteadyChunks.LOGGER.warn("SteadyChunks 泄漏检测: 全局任务计数 {} != 维度计数总和 {}", global, sum);
        }
        return leaks;
    }

    // 诊断访问器
    public int globalTaskCount() { return globalTaskCount.get(); }
    public int dimensionTaskCount(ResourceKey<Level> dim) {
        return dimensionTaskCounts.getOrDefault(dim, new AtomicInteger(0)).get();
    }
    public long totalChunksUnloaded() { return totalChunksUnloaded.get(); }
    public long totalDimensionsUnloaded() { return totalDimensionsUnloaded.get(); }
    public long totalLeaksDetected() { return totalLeaksDetected.get(); }
    public boolean isShutdownMode() { return shutdownMode.get(); }
}
