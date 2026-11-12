package com.mochi_753.steadychunks.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * FULL 最终化细分指标，对应开发计划 §8.4。
 * <p>
 * 测量并预算 FULL 阶段的子阶段耗时：
 * <ul>
 *   <li>ProtoChunk 转换</li>
 *   <li>Heightmap 最终化</li>
 *   <li>POI（兴趣点）登记</li>
 *   <li>方块实体登记</li>
 *   <li>Tick 列表</li>
 *   <li>NeoForge 事件</li>
 *   <li>模组区块加载回调</li>
 * </ul>
 * <p>
 * 每个子阶段按 modid 聚合（minecraft / neoforge / 模组 modid），
 * 用于识别 FULL 整合中哪个环节是主要成本来源。
 * <p>
 * 使用 {@link QuantileEstimator} 估算 P95/P99，{@link LongAdder} 聚合计数。
 */
public final class FullFinalizationMetrics {
    /** 按子阶段 × modid 索引的指标桶 */
    private final ConcurrentHashMap<SubStageKey, SubStageBucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder totalFinalized = new LongAdder();
    private final LongAdder totalFailed = new LongAdder();
    /** FULL 整合总耗时（含所有子阶段） */
    private final QuantileEstimator totalDuration = new QuantileEstimator();

    public FullFinalizationMetrics() {
    }

    /**
     * 记录一次 FULL 子阶段执行。
     *
     * @param stage        子阶段类型
     * @param modid        来源 modid（minecraft / neoforge / 模组 modid）
     * @param durationNanos 耗时（纳秒）
     */
    public void record(FullSubStage stage, String modid, long durationNanos) {
        SubStageKey key = new SubStageKey(stage, modid);
        SubStageBucket b = buckets.computeIfAbsent(key, SubStageBucket::new);
        b.duration.record(durationNanos);
        b.count.increment();
    }

    /**
     * 记录一次 FULL 整合完成（含所有子阶段总耗时）。
     *
     * @param totalNanos 总耗时
     * @param failed     是否失败
     */
    public void recordFinalizeComplete(long totalNanos, boolean failed) {
        totalDuration.record(totalNanos);
        totalFinalized.increment();
        if (failed) {
            totalFailed.increment();
        }
    }

    public SubStageBucket bucket(FullSubStage stage, String modid) {
        return buckets.get(new SubStageKey(stage, modid));
    }

    public Map<SubStageKey, SubStageBucket> allBuckets() {
        return new ConcurrentHashMap<>(buckets);
    }

    public long totalFinalized() {
        return totalFinalized.sum();
    }

    public long totalFailed() {
        return totalFailed.sum();
    }

    public QuantileEstimator totalDuration() {
        return totalDuration;
    }

    public void reset() {
        buckets.clear();
        totalFinalized.reset();
        totalFailed.reset();
        totalDuration.reset();
    }

    /**
     * 生成按子阶段聚合的概览（识别 FULL 主要成本来源）。
     */
    public String categoryOverview() {
        long[] stageTotals = new long[FullSubStage.values().length];
        long[] stageCounts = new long[FullSubStage.values().length];
        long[] stageP99 = new long[FullSubStage.values().length];

        for (var entry : buckets.entrySet()) {
            int i = entry.getKey().stage().ordinal();
            stageTotals[i] += entry.getValue().duration.sumNanos();
            stageCounts[i] += entry.getValue().count.sum();
            stageP99[i] = Math.max(stageP99[i], entry.getValue().duration.quantile(0.99));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## FULL 最终化子阶段概览\n\n");
        sb.append("| 子阶段 | 调用次数 | 总耗时(ms) | P99(ms) |\n");
        sb.append("|---|---|---|---|\n");
        for (FullSubStage stage : FullSubStage.values()) {
            int i = stage.ordinal();
            sb.append(String.format("| %s | %d | %.2f | %.2f |\n",
                    stage.displayName(),
                    stageCounts[i],
                    stageTotals[i] / 1_000_000.0,
                    stageP99[i] / 1_000_000.0));
        }
        return sb.toString();
    }

    /** FULL 最终化子阶段 */
    public enum FullSubStage {
        PROTO_CHUNK_CONVERT("ProtoChunk 转换"),
        HEIGHTMAP_FINALIZE("Heightmap 最终化"),
        POI_REGISTER("POI 登记"),
        BLOCK_ENTITY_REGISTER("方块实体登记"),
        TICK_LIST("Tick 列表"),
        NEOFORGE_EVENT("NeoForge 事件"),
        MOD_CHUNK_LOAD_CALLBACK("模组区块加载回调"),
        OTHER("其他");

        private final String displayName;

        FullSubStage(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 子阶段 × modid 复合键 */
    public record SubStageKey(FullSubStage stage, String modid) {
    }

    /** 单个子阶段指标桶 */
    public static final class SubStageBucket {
        public final SubStageKey key;
        public final QuantileEstimator duration = new QuantileEstimator();
        public final LongAdder count = new LongAdder();

        SubStageBucket(SubStageKey key) {
            this.key = key;
        }
    }
}
