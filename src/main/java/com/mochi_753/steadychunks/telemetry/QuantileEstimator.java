package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.atomic.LongAdder;

/**
 * 分位数估算器，基于 DDSketch 思路简化为对数分桶。
 * <p>
 * 设计目标：固定内存、O(1) 插入、可估算 P50/P90/P95/P99/最大值。
 * 精度：±10%（对性能指标足够，避免 HDR Histogram 的复杂度）。
 * <p>
 * 桶设计（纳秒）：
 * <ul>
 *   <li>0~1μs：单桶（性能不敏感区间）</li>
 *   <li>1μs~10ms：对数分桶，每桶宽度按 2 倍递增</li>
 *   <li>10ms~1s：对数分桶，每桶宽度按 4 倍递增</li>
 *   <li>>1s：单桶</li>
 * </ul>
 * 总桶数约 40，每个 {@link LongAdder}，内存约 320 字节/指标。
 */
public final class QuantileEstimator {
    /** 桶上界（纳秒），升序 */
    private static final long[] BUCKET_BOUNDS;
    static {
        long[] bounds = new long[42];
        int i = 0;
        bounds[i++] = 1_000L;          // 1μs
        long cur = 1_000L;
        // 1μs → 10ms（2 倍递增，约 14 桶）
        while (cur < 10_000_000L && i < bounds.length) {
            cur *= 2;
            bounds[i++] = cur;
        }
        // 10ms → 1s（4 倍递增，约 8 桶）
        while (cur < 1_000_000_000L && i < bounds.length) {
            cur *= 4;
            bounds[i++] = cur;
        }
        // 截断到已填入部分
        long[] trimmed = new long[i];
        System.arraycopy(bounds, 0, trimmed, 0, i);
        BUCKET_BOUNDS = trimmed;
    }

    private final LongAdder[] buckets;
    private final LongAdder count = new LongAdder();
    private final LongAdder sumNanos = new LongAdder();
    private volatile long maxNanos = 0;

    public QuantileEstimator() {
        buckets = new LongAdder[BUCKET_BOUNDS.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /**
     * 记录一个耗时样本（纳秒）。
     */
    public void record(long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        count.increment();
        sumNanos.add(durationNanos);
        // 更新最大值（无锁，允许偶尔漏更新）
        long curMax = maxNanos;
        if (durationNanos > curMax) {
            maxNanos = durationNanos;
        }
        // 找桶
        int idx = bucketIndex(durationNanos);
        buckets[idx].increment();
    }

    private int bucketIndex(long nanos) {
        // 二分查找第一个大于 nanos 的边界
        int lo = 0, hi = BUCKET_BOUNDS.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (BUCKET_BOUNDS[mid] < nanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * 估算分位数（0~1），返回纳秒。{@code q=0.99} 即 P99。
     */
    public long quantile(double q) {
        long total = count.sum();
        if (total == 0) {
            return 0;
        }
        long target = (long) (total * q);
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                // 返回桶中点作为估算
                if (i == 0) {
                    return BUCKET_BOUNDS[0] / 2;
                } else if (i < BUCKET_BOUNDS.length) {
                    return (BUCKET_BOUNDS[i - 1] + BUCKET_BOUNDS[i]) / 2;
                } else {
                    // 最后一个桶（> 最大边界），返回最大边界
                    return BUCKET_BOUNDS[BUCKET_BOUNDS.length - 1];
                }
            }
        }
        return maxNanos;
    }

    public long count() {
        return count.sum();
    }

    public long sumNanos() {
        return sumNanos.sum();
    }

    public long maxNanos() {
        return maxNanos;
    }

    public double meanNanos() {
        long c = count.sum();
        return c == 0 ? 0 : (double) sumNanos.sum() / c;
    }

    public void reset() {
        for (LongAdder b : buckets) {
            b.reset();
        }
        count.reset();
        sumNanos.reset();
        maxNanos = 0;
    }

    /**
     * 返回所有桶边界，供报告导出。
     */
    public static long[] bucketBounds() {
        return BUCKET_BOUNDS.clone();
    }

    /**
     * 返回所有桶计数，供报告导出。
     */
    public long[] bucketCounts() {
        long[] out = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            out[i] = buckets[i].sum();
        }
        return out;
    }
}
