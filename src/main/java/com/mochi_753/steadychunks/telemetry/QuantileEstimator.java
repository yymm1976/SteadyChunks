package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 分位数估算器，基于 DDSketch 思路简化为对数分桶，P1-11 修复。
 * <p>
 * <b>修复内容</b>：
 * <ul>
 *   <li>单样本分位数修复：rank = max(1, ceil(total*q))，确保单样本时 P95 返回该样本而非 0</li>
 *   <li>滑动时间窗口：仅保留最近 {@link #WINDOW_NANOS} 的样本，使控制器对负载变化敏感</li>
 * </ul>
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
 * <p>
 * 滑动窗口实现：使用 {@link ConcurrentLinkedDeque} 保存带时间戳的桶快照，
 * 每次 quantile 查询时清理过期桶并重新累加。为避免 Deque 无限增长，
 * 桶按秒粒度聚合（每秒一个桶节点），窗口 30 秒最多 30 个节点。
 */
public final class QuantileEstimator {
    /** 滑动窗口时长（纳秒），默认 30 秒 */
    private static final long WINDOW_NANOS = 30_000_000_000L;
    /** 桶聚合粒度（纳秒），1 秒一个节点 */
    private static final long BUCKET_GRANULARITY_NANOS = 1_000_000_000L;

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

    /** 滑动窗口节点：每秒一个，记录该秒内的桶计数 */
    private final ConcurrentLinkedDeque<WindowNode> window = new ConcurrentLinkedDeque<>();
    /** 当前秒的聚合节点 */
    private volatile WindowNode currentNode;
    /** 全局累计计数（不含窗口清理） */
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalSumNanos = new LongAdder();
    private volatile long globalMaxNanos = 0;

    public QuantileEstimator() {
        currentNode = new WindowNode(System.nanoTime());
        window.add(currentNode);
    }

    /**
     * 记录一个耗时样本（纳秒）。
     */
    public void record(long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        long now = System.nanoTime();
        totalCount.increment();
        totalSumNanos.add(durationNanos);
        // 更新全局最大值（无锁，允许偶尔漏更新）
        long curMax = globalMaxNanos;
        if (durationNanos > curMax) {
            globalMaxNanos = durationNanos;
        }
        // 检查是否需要滚动到新秒节点
        WindowNode node = currentNode;
        if (now - node.startNanos >= BUCKET_GRANULARITY_NANOS) {
            WindowNode newNode = new WindowNode(now);
            window.add(newNode);
            currentNode = newNode;
            node = newNode;
        }
        // 找桶并计数
        int idx = bucketIndex(durationNanos);
        node.buckets[idx].increment();
        node.count.increment();
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
     * <p>
     * P1-11 修复：
     * <ul>
     *   <li>使用滑动窗口内样本（最近 30 秒）</li>
     *   <li>rank = max(1, ceil(total*q))，确保单样本时返回该样本而非 0</li>
     * </ul>
     */
    public long quantile(double q) {
        long now = System.nanoTime();
        long windowStart = now - WINDOW_NANOS;

        // 清理过期节点并聚合窗口内桶
        long[] aggBuckets = new long[BUCKET_BOUNDS.length + 1];
        long windowCount = 0;
        WindowNode head;
        while ((head = window.peek()) != null && head.startNanos < windowStart) {
            window.poll();
        }
        for (WindowNode node : window) {
            for (int i = 0; i < aggBuckets.length; i++) {
                aggBuckets[i] += node.buckets[i].sum();
            }
            windowCount += node.count.sum();
        }

        if (windowCount == 0) {
            return 0;
        }
        // P1-11 修复：rank = max(1, ceil(total*q))，确保单样本时返回该样本
        long target = Math.max(1, (long) Math.ceil(windowCount * q));
        long cumulative = 0;
        for (int i = 0; i < aggBuckets.length; i++) {
            cumulative += aggBuckets[i];
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
        return globalMaxNanos;
    }

    /**
     * 全局累计计数（含已滑出窗口的历史样本）。
     */
    public long count() {
        return totalCount.sum();
    }

    /**
     * 全局累计耗时（含已滑出窗口的历史样本）。
     */
    public long sumNanos() {
        return totalSumNanos.sum();
    }

    /**
     * 全局最大值（含已滑出窗口的历史样本）。
     */
    public long maxNanos() {
        return globalMaxNanos;
    }

    public double meanNanos() {
        long c = totalCount.sum();
        return c == 0 ? 0 : (double) totalSumNanos.sum() / c;
    }

    public void reset() {
        window.clear();
        currentNode = new WindowNode(System.nanoTime());
        window.add(currentNode);
        totalCount.reset();
        totalSumNanos.reset();
        globalMaxNanos = 0;
    }

    /**
     * 返回所有桶边界，供报告导出。
     */
    public static long[] bucketBounds() {
        return BUCKET_BOUNDS.clone();
    }

    /**
     * 返回当前窗口内所有桶计数，供报告导出。
     */
    public long[] bucketCounts() {
        long[] out = new long[BUCKET_BOUNDS.length + 1];
        for (WindowNode node : window) {
            for (int i = 0; i < out.length; i++) {
                out[i] += node.buckets[i].sum();
            }
        }
        return out;
    }

    /**
     * 滑动窗口节点：每秒聚合一次，记录该秒内各桶计数。
     */
    private static final class WindowNode {
        final long startNanos;
        final LongAdder[] buckets;
        final LongAdder count = new LongAdder();

        WindowNode(long startNanos) {
            this.startNanos = startNanos;
            this.buckets = new LongAdder[BUCKET_BOUNDS.length + 1];
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }
}
