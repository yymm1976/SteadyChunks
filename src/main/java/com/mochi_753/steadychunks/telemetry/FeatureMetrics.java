package com.mochi_753.steadychunks.telemetry;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * FEATURES 阶段细分指标，对应开发计划 §2.4。
 * <p>
 * 将 FEATURES 拆分为：结构 Piece 放置、普通 PlacedFeature、模板读取、StructureProcessor、
 * 方块实体与 NBT、后处理、跨区块读取、跨区块写入，按 registry key 与 modid 统计。
 * <p>
 * 使用 {@link ConcurrentHashMap} 按 {@link ResourceLocation} 索引；
 * modid 从 key 的 namespace 提取，聚合到 {@link ModBucket}。
 */
public final class FeatureMetrics {
    /** 按特征/结构 registry key 索引 */
    private final ConcurrentHashMap<ResourceLocation, FeatureBucket> buckets = new ConcurrentHashMap<>();
    /** 按 modid 聚合 */
    private final ConcurrentHashMap<String, LongAdder> modidAllocations = new ConcurrentHashMap<>();
    private final LongAdder totalPlaced = new LongAdder();
    private final LongAdder totalCrossChunkReads = new LongAdder();
    private final LongAdder totalCrossChunkWrites = new LongAdder();

    /**
     * 记录一次 FEATURES 子项执行。
     *
     * @param featureKey      特征/结构 registry key
     * @param category        子类目（结构 Piece / PlacedFeature / 模板 / Processor / 方块实体 / 后处理）
     * @param durationNanos   耗时
     * @param allocationApprox 近似分配字节数（用于 GC 压力分析）
     * @param crossChunkRead  是否发生跨区块读
     * @param crossChunkWrite 是否发生跨区块写
     */
    public void record(ResourceLocation featureKey, FeatureCategory category,
                       long durationNanos, long allocationApprox,
                       boolean crossChunkRead, boolean crossChunkWrite) {
        FeatureBucket b = buckets.computeIfAbsent(featureKey, FeatureBucket::new);
        b.categoryDuration(category).record(durationNanos);
        b.categoryCount(category).increment();
        b.allocatedBytes.add(allocationApprox);
        if (crossChunkRead) {
            b.crossChunkReads.increment();
            totalCrossChunkReads.increment();
        }
        if (crossChunkWrite) {
            b.crossChunkWrites.increment();
            totalCrossChunkWrites.increment();
        }
        totalPlaced.increment();
        modidAllocations.computeIfAbsent(featureKey.getNamespace(), k -> new LongAdder()).add(allocationApprox);
    }

    public FeatureBucket bucket(ResourceLocation key) {
        return buckets.get(key);
    }

    public Map<ResourceLocation, FeatureBucket> allBuckets() {
        return new ConcurrentHashMap<>(buckets);
    }

    public Map<String, LongAdder> modidAllocations() {
        return new ConcurrentHashMap<>(modidAllocations);
    }

    public long totalPlaced() {
        return totalPlaced.sum();
    }

    public long totalCrossChunkReads() {
        return totalCrossChunkReads.sum();
    }

    public long totalCrossChunkWrites() {
        return totalCrossChunkWrites.sum();
    }

    public void reset() {
        buckets.clear();
        modidAllocations.clear();
        totalPlaced.reset();
        totalCrossChunkReads.reset();
        totalCrossChunkWrites.reset();
    }

    /** FEATURES 子类目 */
    public enum FeatureCategory {
        STRUCTURE_PIECE,
        PLACED_FEATURE,
        TEMPLATE_LOAD,
        STRUCTURE_PROCESSOR,
        BLOCK_ENTITY_NBT,
        POST_PROCESS,
        UNKNOWN_MOD_CALL
    }

    /** 单个特征/结构的指标桶 */
    public static final class FeatureBucket {
        public final ResourceLocation key;
        private final QuantileEstimator[] categoryDurations;
        private final LongAdder[] categoryCounts;
        public final LongAdder allocatedBytes = new LongAdder();
        public final LongAdder crossChunkReads = new LongAdder();
        public final LongAdder crossChunkWrites = new LongAdder();

        FeatureBucket(ResourceLocation key) {
            this.key = key;
            FeatureCategory[] cats = FeatureCategory.values();
            categoryDurations = new QuantileEstimator[cats.length];
            categoryCounts = new LongAdder[cats.length];
            for (int i = 0; i < cats.length; i++) {
                categoryDurations[i] = new QuantileEstimator();
                categoryCounts[i] = new LongAdder();
            }
        }

        public QuantileEstimator categoryDuration(FeatureCategory cat) {
            return categoryDurations[cat.ordinal()];
        }

        public LongAdder categoryCount(FeatureCategory cat) {
            return categoryCounts[cat.ordinal()];
        }
    }
}
