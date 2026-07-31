package com.mochi_753.steadychunks.telemetry;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * ChunkStatus / ChunkStep 阶段指标，对应开发计划 §2.2。
 * <p>
 * 逐阶段采集：排队时间、执行时间、完成数量、异常数量、取消数量、P50/P90/P95/P99/最大值。
 * 使用 {@link QuantileEstimator} 估算分位数，{@link LongAdder} 聚合计数。
 * <p>
 * 1.21.1：{@link ChunkStatus} 是注册类（非枚举），不能用 ordinal 索引数组。
 * 改用 {@link ConcurrentHashMap} 按 ChunkStatus 实例索引，诊断代码对性能不敏感，Map 查找可接受。
 */
public final class StageMetrics {
    /** 按 ChunkStatus 实例索引的单阶段指标 */
    private final ConcurrentHashMap<ChunkStatus, StageBucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder totalCompleted = new LongAdder();
    private final LongAdder totalFailed = new LongAdder();
    private final LongAdder totalCancelled = new LongAdder();

    public StageMetrics() {
        // 懒初始化：buckets 在首次 record 时填充，避免启动期访问注册表顺序问题
    }

    /**
     * 记录一次阶段完成（执行耗时，纳秒）。
     */
    public void recordExecution(ChunkStatus status, long durationNanos) {
        buckets.computeIfAbsent(status, StageBucket::new).execution.record(durationNanos);
        totalCompleted.increment();
    }

    /**
     * 记录一次阶段排队等待耗时（纳秒）。
     */
    public void recordQueueWait(ChunkStatus status, long waitNanos) {
        buckets.computeIfAbsent(status, StageBucket::new).queueWait.record(waitNanos);
    }

    public void recordFailure(ChunkStatus status) {
        buckets.computeIfAbsent(status, StageBucket::new).failed.increment();
        totalFailed.increment();
    }

    public void recordCancellation(ChunkStatus status) {
        buckets.computeIfAbsent(status, StageBucket::new).cancelled.increment();
        totalCancelled.increment();
    }

    public StageBucket bucket(ChunkStatus status) {
        return buckets.computeIfAbsent(status, StageBucket::new);
    }

    public Collection<StageBucket> allBuckets() {
        return buckets.values();
    }

    public long totalCompleted() {
        return totalCompleted.sum();
    }

    public long totalFailed() {
        return totalFailed.sum();
    }

    public long totalCancelled() {
        return totalCancelled.sum();
    }

    public void reset() {
        for (StageBucket b : buckets.values()) {
            b.reset();
        }
        buckets.clear();
        totalCompleted.reset();
        totalFailed.reset();
        totalCancelled.reset();
    }

    /** 单个 ChunkStatus 的指标桶 */
    public static final class StageBucket {
        public final ChunkStatus status;
        public final QuantileEstimator execution = new QuantileEstimator();
        public final QuantileEstimator queueWait = new QuantileEstimator();
        public final LongAdder failed = new LongAdder();
        public final LongAdder cancelled = new LongAdder();

        StageBucket(ChunkStatus status) {
            this.status = status;
        }

        public long completed() {
            return execution.count();
        }

        void reset() {
            execution.reset();
            queueWait.reset();
            failed.reset();
            cancelled.reset();
        }
    }
}
