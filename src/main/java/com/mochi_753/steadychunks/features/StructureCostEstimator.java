package com.mochi_753.steadychunks.features;

import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.StructureMetrics;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构成本估计器，对应开发计划 §7.6 与 §3.7。
 * <p>
 * 基于历史 {@link StructureMetrics} 数据估计单个结构任务的 FEATURES 成本，
 * 供调度器降低超大型结构并行度，避免多个长尾结构同时运行。
 * <p>
 * 成本维度：
 * <ul>
 *   <li>P99 耗时（长尾优先）</li>
 *   <li>平均 Piece 数（复杂度）</li>
 *   <li>Jigsaw 平均节点数（递归深度）</li>
 *   <li>模板查找次数（I/O 成本）</li>
 *   <li>碰撞检查次数（计算成本）</li>
 * </ul>
 * <p>
 * 输出 CostClass：
 * <ul>
 *   <li>LOW：低成本，正常调度</li>
 *   <li>MEDIUM：中等成本，与其他 MEDIUM 串行</li>
 *   <li>HIGH：高成本（长尾结构），同时只允许 1 个</li>
 *   <li>UNKNOWN：历史数据不足，按 LOW 处理（不阻塞生成）</li>
 * </ul>
 * <p>
 * 仅当 {@link StructureMetrics#totalStarts()} 超过 {@link #MIN_SAMPLES} 才生效，
 * 避免冷启动误判。
 */
public final class StructureCostEstimator {
    private static final StructureCostEstimator INSTANCE = new StructureCostEstimator();

    /** 触发成本估计的最小样本数 */
    private static final int MIN_SAMPLES = 5;
    /** HIGH 成本 P99 阈值（纳秒，20ms） */
    private static final long HIGH_P99_NANOS = 20_000_000L;
    /** MEDIUM 成本 P99 阈值（纳秒，5ms） */
    private static final long MEDIUM_P99_NANOS = 5_000_000L;
    /** HIGH 成本平均 Piece 阈值 */
    private static final int HIGH_AVG_PIECES = 80;
    /** MEDIUM 成本平均 Piece 阈值 */
    private static final int MEDIUM_AVG_PIECES = 25;

    /** 缓存成本分类，避免每次调度重复计算 */
    private final ConcurrentHashMap<ResourceLocation, CostClass> cache = new ConcurrentHashMap<>();

    private StructureCostEstimator() {
    }

    public static StructureCostEstimator getInstance() {
        return INSTANCE;
    }

    /**
     * 估计指定结构的成本分类。
     * <p>
     * 历史样本不足时返回 {@link CostClass#UNKNOWN}，调度器按 LOW 处理。
     *
     * @param structureKey 结构 registry key
     * @return 成本分类
     */
    public CostClass estimate(ResourceLocation structureKey) {
        CostClass cached = cache.get(structureKey);
        if (cached != null) {
            return cached;
        }
        CostClass computed = compute(structureKey);
        cache.put(structureKey, computed);
        return computed;
    }

    private CostClass compute(ResourceLocation structureKey) {
        StructureMetrics.StructureBucket b = ChunkFlightRecorder.structures().bucket(structureKey);
        if (b == null) {
            return CostClass.UNKNOWN;
        }
        long starts = b.starts.sum();
        if (starts < MIN_SAMPLES) {
            return CostClass.UNKNOWN;
        }

        long p99 = b.duration.quantile(0.99);
        long avgPieces = b.pieces.sum() / Math.max(1, starts);

        // 任一维度触发 HIGH 即判为 HIGH
        if (p99 >= HIGH_P99_NANOS || avgPieces >= HIGH_AVG_PIECES) {
            return CostClass.HIGH;
        }
        if (p99 >= MEDIUM_P99_NANOS || avgPieces >= MEDIUM_AVG_PIECES) {
            return CostClass.MEDIUM;
        }
        return CostClass.LOW;
    }

    /**
     * 计算指定结构集合的最大允许并发数。
     * <p>
     * HIGH：1（同时只允许 1 个长尾结构运行）<br>
     * MEDIUM：2<br>
     * LOW / UNKNOWN：使用调度器默认值
     *
     * @param structureKey 结构 registry key
     * @param defaultMax   调度器默认并发上限
     * @return 此结构的最大允许并发数
     */
    public int maxConcurrent(ResourceLocation structureKey, int defaultMax) {
        return switch (estimate(structureKey)) {
            case HIGH -> 1;
            case MEDIUM -> Math.min(2, defaultMax);
            case LOW, UNKNOWN -> defaultMax;
        };
    }

    /**
     * 历史指标重置时调用，清空缓存。
     */
    public void invalidate() {
        cache.clear();
    }

    /**
     * 清除单个结构的缓存（结构定义变更时）。
     */
    public void invalidate(ResourceLocation structureKey) {
        cache.remove(structureKey);
    }

    /** 成本分类 */
    public enum CostClass {
        /** 低成本，正常调度 */
        LOW,
        /** 中等成本，与其他 MEDIUM 串行 */
        MEDIUM,
        /** 高成本（长尾结构），同时只允许 1 个 */
        HIGH,
        /** 历史数据不足，按 LOW 处理（不阻塞生成） */
        UNKNOWN
    }
}
