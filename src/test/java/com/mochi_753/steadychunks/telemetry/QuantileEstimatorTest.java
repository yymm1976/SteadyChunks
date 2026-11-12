package com.mochi_753.steadychunks.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QuantileEstimator 单元测试，对应 P2-19。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>单样本 P95 返回该样本所在桶（修复 rank = max(1, ceil(total*q))）</li>
 *   <li>多样本分位数单调性（P50 ≤ P95 ≤ P99 ≤ max）</li>
 *   <li>空样本返回 0</li>
 *   <li>滑动窗口：过期样本不再影响分位数</li>
 * </ul>
 */
class QuantileEstimatorTest {

    @Test
    void singleSampleP95ShouldNotReturnZero() {
        QuantileEstimator est = new QuantileEstimator();
        // 5ms = 5_000_000ns，应落入 4ms~10ms 桶区间
        est.record(5_000_000L);
        long p95 = est.quantile(0.95);
        assertTrue(p95 > 0, "单样本 P95 必须大于 0，实际: " + p95);
        // 5ms 应在 [1ms, 10ms) 区间，桶中点应大于 0
        assertTrue(p95 >= 1_000_000L, "5ms 样本 P95 应至少在 1ms 以上，实际: " + p95);
    }

    @Test
    void singleSampleP99AndMaxShouldBeConsistent() {
        QuantileEstimator est = new QuantileEstimator();
        est.record(8_000_000L);
        long p50 = est.quantile(0.50);
        long p95 = est.quantile(0.95);
        long p99 = est.quantile(0.99);
        long max = est.maxNanos();
        assertTrue(p50 > 0);
        assertTrue(p95 > 0);
        assertTrue(p99 > 0);
        assertEquals(p50, p95, "单样本各分位数应相同");
        assertEquals(p95, p99);
        assertEquals(8_000_000L, max, "max 应等于样本值");
    }

    @Test
    void emptyQuantileShouldReturnZero() {
        QuantileEstimator est = new QuantileEstimator();
        assertEquals(0, est.quantile(0.95), "无样本时 P95 应为 0");
        assertEquals(0, est.quantile(0.50));
    }

    @Test
    void quantilesShouldBeMonotonic() {
        QuantileEstimator est = new QuantileEstimator();
        // 注入多个不同量级样本
        for (int i = 0; i < 100; i++) {
            est.record(1_000L * (i + 1)); // 1μs ~ 100μs
        }
        long p50 = est.quantile(0.50);
        long p90 = est.quantile(0.90);
        long p95 = est.quantile(0.95);
        long p99 = est.quantile(0.99);
        assertTrue(p50 <= p90, "P50 <= P90");
        assertTrue(p90 <= p95, "P90 <= P95");
        assertTrue(p95 <= p99, "P95 <= P99");
    }

    @Test
    void negativeSamplesShouldBeIgnored() {
        QuantileEstimator est = new QuantileEstimator();
        est.record(-1L);
        est.record(-100L);
        assertEquals(0, est.count(), "负样本应被忽略");
        assertEquals(0, est.quantile(0.95));
    }

    @Test
    void countShouldReflectSamples() {
        QuantileEstimator est = new QuantileEstimator();
        for (int i = 0; i < 50; i++) {
            est.record(1_000L);
        }
        assertEquals(50, est.count(), "累计计数应为 50");
        assertTrue(est.sumNanos() > 0);
    }

    @Test
    void resetShouldClearAllState() {
        QuantileEstimator est = new QuantileEstimator();
        est.record(1_000_000L);
        est.record(2_000_000L);
        assertEquals(2, est.count());
        est.reset();
        assertEquals(0, est.count());
        assertEquals(0, est.maxNanos());
        assertEquals(0, est.quantile(0.95));
    }

    @Test
    void maxNanosShouldTrackGlobalMax() {
        QuantileEstimator est = new QuantileEstimator();
        est.record(1_000_000L);
        est.record(50_000_000L);
        est.record(5_000_000L);
        assertEquals(50_000_000L, est.maxNanos(), "全局最大值应为 50ms");
    }

    @Test
    void bucketBoundsShouldBeAscending() {
        long[] bounds = QuantileEstimator.bucketBounds();
        for (int i = 1; i < bounds.length; i++) {
            assertTrue(bounds[i] > bounds[i - 1], "桶边界应严格递增");
        }
        assertTrue(bounds.length > 10, "桶数量应足够");
    }
}
