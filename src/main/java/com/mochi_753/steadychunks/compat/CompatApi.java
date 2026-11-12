package com.mochi_753.steadychunks.compat;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.bootstrap.ModuleStates;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 公开兼容 API，对应开发计划 §10.3。
 * <p>
 * 提供可选的只读或协作接口，供第三方模组适配 SteadyChunks：
 * <ul>
 *   <li>注册阶段所有权</li>
 *   <li>注册世界生成任务安全等级</li>
 *   <li>注册结构成本估计器</li>
 *   <li>提供任务优先级提示</li>
 *   <li>提供模块状态</li>
 *   <li>订阅缓存失效</li>
 *   <li>注册可安全并行的自定义生成器</li>
 * </ul>
 * <p>
 * API 必须是可选的；未适配模组仍使用保守路径（计划 §10.3）。
 * <p>
 * 所有方法线程安全，使用 ConcurrentHashMap 与 CopyOnWriteArrayList。
 */
public final class CompatApi {
    private static final CompatApi INSTANCE = new CompatApi();

    /** 按 modid 注册的阶段所有权 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<ChunkStatus, String>> stageOwnership = new ConcurrentHashMap<>();
    /** 按 modid 注册的安全等级 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<ResourceLocation, SafetyLevel>> safetyLevels = new ConcurrentHashMap<>();
    /** 按 modid 注册的结构成本估计器 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<ResourceLocation, CostEstimate>> costEstimates = new ConcurrentHashMap<>();
    /** 按 modid 注册的优先级提示 */
    private final ConcurrentHashMap<String, PriorityHint> priorityHints = new ConcurrentHashMap<>();
    /** 缓存失效订阅者 */
    private final CopyOnWriteArrayList<Consumer<CacheInvalidationEvent>> cacheInvalidationSubscribers = new CopyOnWriteArrayList<>();
    /** 按 modid 注册的可安全并行生成器 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ParallelGeneratorEntry>> parallelGenerators = new ConcurrentHashMap<>();

    /** 模块状态快照（启动时设置） */
    private volatile ModuleStates moduleStates;

    private CompatApi() {
    }

    public static CompatApi getInstance() {
        return INSTANCE;
    }

    /**
     * 设置模块状态快照（启动时由 bootstrap 调用）。
     */
    public void setModuleStates(ModuleStates states) {
        this.moduleStates = states;
    }

    // ===== 阶段所有权注册 =====

    /**
     * 注册某阶段的资源所有权。
     * <p>
     * 第三方模组声明自己拥有某 ChunkStatus 的内部并发管理权，
     * SteadyChunks 将不对此阶段注入并发限制。
     *
     * @param modid  注册方 modid
     * @param status 目标阶段
     * @param owner  所有者标识（通常为 modid）
     */
    public void registerStageOwnership(String modid, ChunkStatus status, String owner) {
        stageOwnership.computeIfAbsent(modid, k -> new ConcurrentHashMap<>()).put(status, owner);
        SteadyChunks.LOGGER.info("SteadyChunks 兼容 API: {} 注册阶段所有权 {} = {}", modid, status, owner);
    }

    /**
     * 查询某阶段的所有权。
     *
     * @return 所有者标识，null 表示 SteadyChunks 管理
     */
    public String getStageOwnership(ChunkStatus status) {
        for (var map : stageOwnership.values()) {
            String owner = map.get(status);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    // ===== 安全等级注册 =====

    /**
     * 注册世界生成任务的安全等级。
     * <p>
     * 第三方模组声明自家生成器的线程安全能力，SteadyChunks 据此决定是否并行。
     *
     * @param modid       注册方 modid
     * @param generatorId 生成器标识
     * @param level       安全等级
     */
    public void registerSafetyLevel(String modid, ResourceLocation generatorId, SafetyLevel level) {
        safetyLevels.computeIfAbsent(modid, k -> new ConcurrentHashMap<>()).put(generatorId, level);
        SteadyChunks.LOGGER.info("SteadyChunks 兼容 API: {} 注册安全等级 {} = {}", modid, generatorId, level);
    }

    /**
     * 查询生成器的安全等级。
     *
     * @return 安全等级，未注册返回 {@link SafetyLevel#UNKNOWN}
     */
    public SafetyLevel getSafetyLevel(ResourceLocation generatorId) {
        for (var map : safetyLevels.values()) {
            SafetyLevel level = map.get(generatorId);
            if (level != null) {
                return level;
            }
        }
        return SafetyLevel.UNKNOWN;
    }

    // ===== 结构成本估计器注册 =====

    /**
     * 注册结构成本估计。
     * <p>
     * 第三方模组声明自家结构的大致成本，SteadyChunks 据此调整并行度。
     *
     * @param modid        注册方 modid
     * @param structureKey 结构标识
     * @param estimate     成本估计
     */
    public void registerCostEstimate(String modid, ResourceLocation structureKey, CostEstimate estimate) {
        costEstimates.computeIfAbsent(modid, k -> new ConcurrentHashMap<>()).put(structureKey, estimate);
        SteadyChunks.LOGGER.info("SteadyChunks 兼容 API: {} 注册结构成本 {} = {}", modid, structureKey, estimate);
    }

    /**
     * 查询结构成本估计。
     *
     * @return 成本估计，未注册返回 null
     */
    public CostEstimate getCostEstimate(ResourceLocation structureKey) {
        for (var map : costEstimates.values()) {
            CostEstimate estimate = map.get(structureKey);
            if (estimate != null) {
                return estimate;
            }
        }
        return null;
    }

    // ===== 优先级提示 =====

    /**
     * 提供任务优先级提示。
     * <p>
     * 第三方模组声明某类任务应优先或延后处理。
     *
     * @param modid 注册方 modid
     * @param hint  优先级提示
     */
    public void providePriorityHint(String modid, PriorityHint hint) {
        priorityHints.put(modid, hint);
        SteadyChunks.LOGGER.info("SteadyChunks 兼容 API: {} 提供优先级提示 {}", modid, hint);
    }

    public PriorityHint getPriorityHint(String modid) {
        return priorityHints.get(modid);
    }

    // ===== 模块状态查询 =====

    /**
     * 提供模块状态（只读）。
     *
     * @return 模块状态快照，未初始化返回 null
     */
    public ModuleStates getModuleStates() {
        return moduleStates;
    }

    // ===== 缓存失效订阅 =====

    /**
     * 订阅缓存失效事件。
     * <p>
     * 当 SteadyChunks 缓存失效（数据包重载、维度卸载等）时通知订阅者。
     *
     * @param subscriber 订阅回调
     */
    public void subscribeCacheInvalidation(Consumer<CacheInvalidationEvent> subscriber) {
        cacheInvalidationSubscribers.add(subscriber);
    }

    /**
     * 触发缓存失效事件（SteadyChunks 内部调用）。
     */
    public void fireCacheInvalidation(CacheInvalidationEvent event) {
        for (Consumer<CacheInvalidationEvent> subscriber : cacheInvalidationSubscribers) {
            try {
                subscriber.accept(event);
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("SteadyChunks 兼容 API: 缓存失效订阅者异常: {}", t.getMessage());
            }
        }
    }

    // ===== 可安全并行生成器注册 =====

    /**
     * 注册可安全并行的自定义生成器。
     * <p>
     * 第三方模组声明自家生成器可安全并行，SteadyChunks 将允许并行执行。
     *
     * @param modid      注册方 modid
     * @param generatorId 生成器标识
     * @param safetyLevel 安全等级（必须 >= {@link SafetyLevel#B}）
     */
    public void registerParallelGenerator(String modid, ResourceLocation generatorId, SafetyLevel safetyLevel) {
        if (safetyLevel.ordinal() < SafetyLevel.B.ordinal()) {
            SteadyChunks.LOGGER.warn("SteadyChunks 兼容 API: {} 生成器 {} 安全等级不足，未注册并行", modid, generatorId);
            return;
        }
        parallelGenerators.computeIfAbsent(modid, k -> new CopyOnWriteArrayList<>())
                .add(new ParallelGeneratorEntry(generatorId, safetyLevel));
        SteadyChunks.LOGGER.info("SteadyChunks 兼容 API: {} 注册可并行生成器 {} = {}", modid, generatorId, safetyLevel);
    }

    /**
     * 查询某生成器是否可安全并行。
     */
    public boolean isParallelSafe(ResourceLocation generatorId) {
        for (List<ParallelGeneratorEntry> list : parallelGenerators.values()) {
            for (ParallelGeneratorEntry e : list) {
                if (e.generatorId().equals(generatorId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 安全等级（对应计划 §5.4） */
    public enum SafetyLevel {
        /** 已知可并行，且不进行共享可变世界写入 */
        A,
        /** 在同一 Chunk 或受控邻域内可并行 */
        B,
        /** 依赖模组实现，需兼容白名单或运行时降级 */
        C,
        /** 必须串行或在原版指定线程执行 */
        D,
        /** 未知（保守按 D 处理） */
        UNKNOWN;

        /**
         * 是否允许并行。
         */
        public boolean allowParallel() {
            return this == A || this == B;
        }
    }

    /** 结构成本估计 */
    public record CostEstimate(
            ResourceLocation structureKey,
            CostClass costClass,
            int estimatedPieceCount,
            long estimatedDurationNanos
    ) {
    }

    /** 成本分类（与 StructureCostEstimator.CostClass 对齐） */
    public enum CostClass {
        LOW, MEDIUM, HIGH, UNKNOWN
    }

    /** 优先级提示 */
    public record PriorityHint(
            String modid,
            PriorityDirection direction,
            String reason
    ) {
    }

    /** 优先级方向 */
    public enum PriorityDirection {
        HIGHER, LOWER, NORMAL
    }

    /** 缓存失效事件 */
    public record CacheInvalidationEvent(
            InvalidationReason reason,
            ResourceLocation dimension,
            UUID playerId
    ) {
    }

    /** 缓存失效原因 */
    public enum InvalidationReason {
        DATAPACK_RELOAD,
        DIMENSION_UNLOAD,
        PLAYER_DISCONNECT,
        CONFIG_CHANGE,
        FULL_RESET
    }

    /** 可并行生成器条目 */
    private record ParallelGeneratorEntry(
            ResourceLocation generatorId,
            SafetyLevel safetyLevel
    ) {
    }
}
