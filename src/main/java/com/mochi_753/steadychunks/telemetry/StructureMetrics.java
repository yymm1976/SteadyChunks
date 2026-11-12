package com.mochi_753.steadychunks.telemetry;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 结构规划指标，对应开发计划 §2.3。
 * <p>
 * 按结构 registry key 和 modid 统计：
 * 候选检查次数、成功起点数、失败原因、Jigsaw 展开节点数、Piece 数、
 * 最大递归深度、模板查找次数、高度查询次数、碰撞判断次数、单次最大耗时。
 * <p>
 * 由于结构类型动态来自 datapack，使用 {@link ConcurrentHashMap} 按 {@link ResourceLocation} 索引，
 * 而非数组（避免 datapack reload 后索引失效）。
 */
public final class StructureMetrics {
    private final ConcurrentHashMap<ResourceLocation, StructureBucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder totalCandidates = new LongAdder();
    private final LongAdder totalStarts = new LongAdder();
    private final LongAdder totalPieces = new LongAdder();

    /**
     * 记录一次结构规划尝试。
     *
     * @param structureKey 结构 registry key
     * @param candidate    是否进入候选检查
     * @param success      是否成功选址
     * @param pieceCount   最终 Piece 数（失败为 0）
     * @param jigsawNodes  Jigsaw 展开节点数
     * @param maxDepth     最大递归深度
     * @param templateLookups 模板查找次数
     * @param heightQueries   高度查询次数
     * @param collisionChecks 碰撞判断次数
     * @param durationNanos 总耗时
     */
    public void record(ResourceLocation structureKey, boolean candidate, boolean success,
                       int pieceCount, int jigsawNodes, int maxDepth,
                       int templateLookups, int heightQueries, int collisionChecks,
                       long durationNanos) {
        StructureBucket b = buckets.computeIfAbsent(structureKey, StructureBucket::new);
        if (candidate) {
            b.candidates.increment();
            totalCandidates.increment();
        }
        if (success) {
            b.starts.increment();
            totalStarts.increment();
            b.pieces.add(pieceCount);
            totalPieces.add(pieceCount);
        }
        b.jigsawNodes.add(jigsawNodes);
        // AtomicInteger 无 accumulate 方法，用 getAndUpdate 实现最大值更新
        b.maxDepth.getAndUpdate(current -> Math.max(current, maxDepth));
        b.templateLookups.add(templateLookups);
        b.heightQueries.add(heightQueries);
        b.collisionChecks.add(collisionChecks);
        b.duration.record(durationNanos);
    }

    public StructureBucket bucket(ResourceLocation key) {
        return buckets.get(key);
    }

    public Map<ResourceLocation, StructureBucket> allBuckets() {
        return new ConcurrentHashMap<>(buckets);
    }

    public long totalCandidates() {
        return totalCandidates.sum();
    }

    public long totalStarts() {
        return totalStarts.sum();
    }

    public long totalPieces() {
        return totalPieces.sum();
    }

    public void reset() {
        buckets.clear();
        totalCandidates.reset();
        totalStarts.reset();
        totalPieces.reset();
    }

    /** 单个结构的指标桶 */
    public static final class StructureBucket {
        public final ResourceLocation key;
        public final LongAdder candidates = new LongAdder();
        public final LongAdder starts = new LongAdder();
        public final LongAdder pieces = new LongAdder();
        public final LongAdder jigsawNodes = new LongAdder();
        public final java.util.concurrent.atomic.AtomicInteger maxDepth = new java.util.concurrent.atomic.AtomicInteger();
        public final LongAdder templateLookups = new LongAdder();
        public final LongAdder heightQueries = new LongAdder();
        public final LongAdder collisionChecks = new LongAdder();
        public final QuantileEstimator duration = new QuantileEstimator();

        StructureBucket(ResourceLocation key) {
            this.key = key;
        }
    }
}
