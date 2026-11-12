package com.mochi_753.steadychunks.structure;

import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.QuantileEstimator;
import com.mochi_753.steadychunks.telemetry.StructureMetrics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 结构热点排名器，对应开发计划 §6.1。
 * <p>
 * 基于 {@link StructureMetrics} 采集的数据，按多个维度生成结构热点排名：
 * <ul>
 *   <li>P99 耗时（长尾优先）</li>
 *   <li>总耗时（全局成本）</li>
 *   <li>候选失败率（选址效率）</li>
 *   <li>平均 Piece 数（复杂度）</li>
 *   <li>模板查找次数（I/O 热点）</li>
 *   <li>碰撞判断次数（计算热点）</li>
 * </ul>
 * <p>
 * 输出包含 modid（从 ResourceLocation.namespace 推断），便于定位来源模组。
 * <p>
 * 仅诊断用途，不改变生成行为。
 */
public final class StructureHotspotRanker {

    private StructureHotspotRanker() {
    }

    /**
     * 生成按指定维度排序的结构热点列表。
     *
     * @param dimension 排序维度
     * @param limit     最多返回条目数
     * @return 热点条目列表（已排序）
     */
    public static List<HotspotEntry> rank(RankDimension dimension, int limit) {
        Map<ResourceLocation, StructureMetrics.StructureBucket> buckets =
                ChunkFlightRecorder.structures().allBuckets();

        List<HotspotEntry> entries = new ArrayList<>(buckets.size());
        for (var entry : buckets.entrySet()) {
            ResourceLocation key = entry.getKey();
            StructureMetrics.StructureBucket b = entry.getValue();
            long candidates = b.candidates.sum();
            long starts = b.starts.sum();
            long pieces = b.pieces.sum();
            long p99 = b.duration.quantile(0.99);
            long maxNanos = b.duration.maxNanos();
            long totalNanos = b.duration.sumNanos();
            long templateLookups = b.templateLookups.sum();
            long heightQueries = b.heightQueries.sum();
            long collisionChecks = b.collisionChecks.sum();
            int maxDepth = b.maxDepth.get();

            entries.add(new HotspotEntry(
                    key, key.getNamespace(),
                    candidates, starts, pieces,
                    p99, maxNanos, totalNanos,
                    templateLookups, heightQueries, collisionChecks,
                    maxDepth,
                    b.duration
            ));
        }

        Comparator<HotspotEntry> cmp = switch (dimension) {
            case P99_DURATION -> Comparator.comparingLong(HotspotEntry::p99Nanos).reversed();
            case TOTAL_DURATION -> Comparator.comparingLong(HotspotEntry::totalNanos).reversed();
            case FAILURE_RATE -> Comparator.comparingDouble(
                    e -> e.candidates() > 0 ? -(double) (e.candidates() - e.starts()) / e.candidates() : 0);
            case AVG_PIECES -> Comparator.comparingLong(
                    e -> e.starts() > 0 ? -(e.pieces() / e.starts()) : 0);
            case TEMPLATE_LOOKUPS -> Comparator.comparingLong(HotspotEntry::templateLookups).reversed();
            case COLLISION_CHECKS -> Comparator.comparingLong(HotspotEntry::collisionChecks).reversed();
        };
        entries.sort(cmp);
        if (entries.size() > limit) {
            return new ArrayList<>(entries.subList(0, limit));
        }
        return entries;
    }

    /**
     * 生成人类可读的 Markdown 热点报告。
     */
    public static String toMarkdown(List<HotspotEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 结构热点排名\n\n");
        sb.append("| 结构 | modid | 候选 | 成功 | 失败率 | P99(ms) | 最大(ms) | 总(ms) | Piece | 模板查找 | 碰撞 | 最大深度 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (HotspotEntry e : entries) {
            double failRate = e.candidates() > 0
                    ? (double) (e.candidates() - e.starts()) / e.candidates() * 100 : 0;
            long avgPieces = e.starts() > 0 ? e.pieces() / e.starts() : 0;
            sb.append(String.format("| %s | %s | %d | %d | %.1f%% | %.2f | %.2f | %.2f | %d | %d | %d | %d |\n",
                    e.structureKey(), e.modid(),
                    e.candidates(), e.starts(), failRate,
                    e.p99Nanos() / 1_000_000.0,
                    e.maxNanos() / 1_000_000.0,
                    e.totalNanos() / 1_000_000.0,
                    avgPieces,
                    e.templateLookups(), e.collisionChecks(), e.maxDepth()));
        }
        return sb.toString();
    }

    /** 排序维度 */
    public enum RankDimension {
        P99_DURATION,
        TOTAL_DURATION,
        FAILURE_RATE,
        AVG_PIECES,
        TEMPLATE_LOOKUPS,
        COLLISION_CHECKS
    }

    /** 单个结构的热点条目 */
    public record HotspotEntry(
            ResourceLocation structureKey,
            String modid,
            long candidates,
            long starts,
            long pieces,
            long p99Nanos,
            long maxNanos,
            long totalNanos,
            long templateLookups,
            long heightQueries,
            long collisionChecks,
            int maxDepth,
            QuantileEstimator duration
    ) {
    }
}
