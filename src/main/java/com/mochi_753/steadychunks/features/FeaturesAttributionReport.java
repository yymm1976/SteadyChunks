package com.mochi_753.steadychunks.features;

import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.FeatureMetrics;
import com.mochi_753.steadychunks.telemetry.QuantileEstimator;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * FEATURES 责任归因报告，对应开发计划 §7.1。
 * <p>
 * 将 FEATURES 阶段耗时归因到以下类别（基于 {@link FeatureMetrics} 采集）：
 * <ul>
 *   <li>结构放置 / 结构 Piece</li>
 *   <li>模板读取</li>
 *   <li>ProcessorList / 单个 Processor</li>
 *   <li>PlacedFeature / ConfiguredFeature</li>
 *   <li>方块实体 / NBT</li>
 *   <li>后处理</li>
 *   <li>跨区块访问</li>
 *   <li>未知模组调用</li>
 * </ul>
 * <p>
 * 按总耗时、P99、分配字节、跨区块读写次数多维排序，输出 Markdown 报告。
 * 仅诊断用途，不改变生成行为。
 */
public final class FeaturesAttributionReport {

    private FeaturesAttributionReport() {
    }

    /**
     * 生成按指定维度排序的归因条目列表。
     */
    public static List<AttributionEntry> rank(AttributionDimension dimension, int limit) {
        Map<ResourceLocation, FeatureMetrics.FeatureBucket> buckets =
                ChunkFlightRecorder.features().allBuckets();

        List<AttributionEntry> entries = new ArrayList<>(buckets.size());
        for (var entry : buckets.entrySet()) {
            ResourceLocation key = entry.getKey();
            FeatureMetrics.FeatureBucket b = entry.getValue();

            long totalNanos = 0L;
            long p99Nanos = 0L;
            long maxNanos = 0L;
            long totalCount = 0L;
            for (FeatureMetrics.FeatureCategory cat : FeatureMetrics.FeatureCategory.values()) {
                QuantileEstimator q = b.categoryDuration(cat);
                totalNanos += q.sumNanos();
                p99Nanos = Math.max(p99Nanos, q.quantile(0.99));
                maxNanos = Math.max(maxNanos, q.maxNanos());
                totalCount += b.categoryCount(cat).sum();
            }

            entries.add(new AttributionEntry(
                    key, key.getNamespace(),
                    totalCount, totalNanos, p99Nanos, maxNanos,
                    b.allocatedBytes.sum(),
                    b.crossChunkReads.sum(), b.crossChunkWrites.sum(),
                    b
            ));
        }

        Comparator<AttributionEntry> cmp = switch (dimension) {
            case TOTAL_DURATION -> Comparator.comparingLong(AttributionEntry::totalNanos).reversed();
            case P99_DURATION -> Comparator.comparingLong(AttributionEntry::p99Nanos).reversed();
            case ALLOCATIONS -> Comparator.comparingLong(AttributionEntry::allocatedBytes).reversed();
            case CROSS_CHUNK_READS -> Comparator.comparingLong(AttributionEntry::crossChunkReads).reversed();
            case CROSS_CHUNK_WRITES -> Comparator.comparingLong(AttributionEntry::crossChunkWrites).reversed();
            case CALL_COUNT -> Comparator.comparingLong(AttributionEntry::callCount).reversed();
        };
        entries.sort(cmp);
        if (entries.size() > limit) {
            return new ArrayList<>(entries.subList(0, limit));
        }
        return entries;
    }

    /**
     * 生成人类可读的 Markdown 归因报告。
     */
    public static String toMarkdown(List<AttributionEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("## FEATURES 责任归因报告\n\n");
        sb.append("| 特征/结构 | modid | 调用次数 | 总耗时(ms) | P99(ms) | 最大(ms) | 分配(KB) | 跨区块读 | 跨区块写 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (AttributionEntry e : entries) {
            sb.append(String.format("| %s | %s | %d | %.2f | %.2f | %.2f | %.1f | %d | %d |\n",
                    e.featureKey(), e.modid(),
                    e.callCount(),
                    e.totalNanos() / 1_000_000.0,
                    e.p99Nanos() / 1_000_000.0,
                    e.maxNanos() / 1_000_000.0,
                    e.allocatedBytes() / 1024.0,
                    e.crossChunkReads(), e.crossChunkWrites()));
        }
        return sb.toString();
    }

    /**
     * 生成按子类目聚合的概览（识别 FEATURES 主要成本来源）。
     */
    public static String categoryOverview() {
        Map<ResourceLocation, FeatureMetrics.FeatureBucket> buckets =
                ChunkFlightRecorder.features().allBuckets();

        long[] categoryTotals = new long[FeatureMetrics.FeatureCategory.values().length];
        long[] categoryCounts = new long[FeatureMetrics.FeatureCategory.values().length];
        long[] categoryP99 = new long[FeatureMetrics.FeatureCategory.values().length];

        for (FeatureMetrics.FeatureBucket b : buckets.values()) {
            for (FeatureMetrics.FeatureCategory cat : FeatureMetrics.FeatureCategory.values()) {
                int i = cat.ordinal();
                categoryTotals[i] += b.categoryDuration(cat).sumNanos();
                categoryCounts[i] += b.categoryCount(cat).sum();
                categoryP99[i] = Math.max(categoryP99[i], b.categoryDuration(cat).quantile(0.99));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## FEATURES 子类目概览\n\n");
        sb.append("| 类目 | 调用次数 | 总耗时(ms) | P99(ms) |\n");
        sb.append("|---|---|---|---|\n");
        for (FeatureMetrics.FeatureCategory cat : FeatureMetrics.FeatureCategory.values()) {
            int i = cat.ordinal();
            sb.append(String.format("| %s | %d | %.2f | %.2f |\n",
                    cat.name(),
                    categoryCounts[i],
                    categoryTotals[i] / 1_000_000.0,
                    categoryP99[i] / 1_000_000.0));
        }
        return sb.toString();
    }

    /** 排序维度 */
    public enum AttributionDimension {
        TOTAL_DURATION,
        P99_DURATION,
        ALLOCATIONS,
        CROSS_CHUNK_READS,
        CROSS_CHUNK_WRITES,
        CALL_COUNT
    }

    /** 单个特征/结构的归因条目 */
    public record AttributionEntry(
            ResourceLocation featureKey,
            String modid,
            long callCount,
            long totalNanos,
            long p99Nanos,
            long maxNanos,
            long allocatedBytes,
            long crossChunkReads,
            long crossChunkWrites,
            FeatureMetrics.FeatureBucket bucket
    ) {
    }
}
